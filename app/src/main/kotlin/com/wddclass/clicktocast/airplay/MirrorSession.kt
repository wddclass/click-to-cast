package com.wddclass.clicktocast.airplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.widget.TextView
import com.wddclass.clicktocast.dlna.DmrLog
import com.wddclass.clicktocast.ui.MirrorActivity
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.SICBlockCipher
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One AirPlay screen mirror: FairPlay-unwrapped AES-CTR video into MediaCodec,
 * NTP timing towards the phone, and a best-effort AAC/AAC-ELD audio stream.
 */
class MirrorSession(
    context: Context,
    private val openActivity: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val decodeThread = HandlerThread("airplay-decode")
    private val decode: Handler
    private val gate = Any()

    @Volatile private var alive = true
    @Volatile private var sessionToken = 0
    @Volatile private var statusText = "正在接收屏幕镜像"
    @Volatile private var statusView: TextView? = null
    @Volatile var onPresented: (() -> Unit)? = null

    private var timingSocket: DatagramSocket? = null
    private var timingRunning = false
    private var timingThread: Thread? = null
    private var timingLogs = 0
    private var videoLogs = 0
    private var audioLogs = 0
    private var opened = false
    private var presented = false

    private var videoCipher: ContinualCtr? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioKey: ByteArray? = null
    private var audioIv: ByteArray? = null
    private var audioCt = 0
    private var audioFailed = false
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var width = 1920
    private var height = 1080
    private var pendingPrefix: ByteArray? = null
    private var latestFrame: ByteArray? = null
    private var videoSocket: Socket? = null

    init {
        active = this
        decodeThread.start()
        decode = Handler(decodeThread.looper)
    }

    fun attachTiming(socket: DatagramSocket) {
        timingSocket = socket
    }

    fun armVideo(
        rawKey: ByteArray,
        ecdhSecret: ByteArray?,
        streamConnectionId: Long,
        remoteIp: String,
        clientTimingPort: Int,
    ) {
        val mixed = MirrorKeys.mixWithEcdh(rawKey, ecdhSecret)
        val (key, iv) = MirrorKeys.streamMaterial(mixed, streamConnectionId)
        DmrLog.i(
            "airplay mirror video stream=$streamConnectionId ecdh=${ecdhSecret != null} " +
                "keyPrefix=${AirPlayKeys.hex(key).take(8)} ivPrefix=${AirPlayKeys.hex(iv).take(8)}",
        )
        decode.post {
            videoCipher = ContinualCtr(key, iv)
            latestFrame = null
            pendingPrefix = null
        }
        startTiming(remoteIp, clientTimingPort)
        show()
    }

    /** NTP toward the phone. The mirror view stays closed until a type 110 stream arrives. */
    fun noteTiming(remoteIp: String, clientTimingPort: Int) {
        if (clientTimingPort <= 0) return
        DmrLog.i("airplay timing from session SETUP to $remoteIp:$clientTimingPort")
        startTiming(remoteIp, clientTimingPort)
    }

    fun armAudio(rawKey: ByteArray, ecdhSecret: ByteArray?, eiv: ByteArray?, ct: Int) {
        val mixed = MirrorKeys.mixWithEcdh(rawKey, ecdhSecret)
        audioKey = mixed
        audioIv = eiv?.copyOf(16)
        audioCt = ct
        audioFailed = false
        DmrLog.i("airplay mirror audio ct=$ct eiv=${eiv?.size ?: 0} keyPrefix=${AirPlayKeys.hex(mixed).take(8)}")
        decode.post { startAudioCodec() }
    }

    fun fail(message: String) {
        statusText = message
        DmrLog.i("airplay mirror fail: $message")
        show()
        statusView?.post { statusView?.text = statusText }
    }

    fun show() {
        if (opened) return
        opened = true
        DmrLog.i("airplay mirror opening activity")
        try {
            openActivity()
        } catch (error: Throwable) {
            DmrLog.i("airplay mirror activity failed: ${error.message}")
        }
    }

    fun bindStatus(view: TextView) {
        statusView = view
        view.text = statusText
    }

    fun setSurface(next: Surface) {
        decode.post {
            surface = next
            DmrLog.i("airplay mirror surface ready")
            startVideoCodec()
        }
    }

    fun clearSurface() {
        decode.post {
            surface = null
            releaseVideoCodec()
            DmrLog.i("airplay mirror surface cleared")
        }
    }

    fun onTiming(packet: ByteArray, address: InetAddress, port: Int): ByteArray? {
        timingLogs++
        if (timingLogs <= 4 || timingLogs % 30 == 0) {
            DmrLog.i(
                "airplay timing recv ${packet.size} from ${address.hostAddress}:$port " +
                    "hex=${AirPlayKeys.hexPrefix(packet, 8)}",
            )
        }
        if (packet.size >= 8 && packet[0] == 0x80.toByte() && (packet[1].toInt() and 0xFF) == 0xD2) {
            val now = System.currentTimeMillis() * 1_000_000L
            DmrLog.i("airplay timing reply d3 to ${address.hostAddress}:$port")
            return MirrorTiming.reply(packet, now, now)
        }
        return null
    }

    fun readVideo(socket: Socket) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        DmrLog.i("airplay mirror connected from $remote")
        videoSocket = socket
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 0
            val input = socket.getInputStream()
            val token = sessionToken
            while (alive && token == sessionToken) {
                val headerBytes = readFully(input, 128) ?: break
                val header = MirrorPackets.header(headerBytes) ?: break
                if (header.payloadSize < 0 || header.payloadSize > 8_000_000) {
                    DmrLog.i("airplay mirror bad payload ${header.payloadSize}")
                    break
                }
                val payload = if (header.payloadSize == 0) {
                    ByteArray(0)
                } else {
                    readFully(input, header.payloadSize) ?: break
                }
                videoLogs++
                if (videoLogs <= 6 || header.type != 0 || videoLogs % 120 == 0) {
                    DmrLog.i(
                        "airplay mirror packet type=${header.type} option=${header.option} " +
                            "bytes=${header.payloadSize} ${header.width}x${header.height} ntp=${header.ntpRaw}",
                    )
                }
                val captured = header
                val body = payload
                decode.post { onVideo(captured, body) }
            }
        } catch (error: Throwable) {
            if (alive) DmrLog.i("airplay mirror socket ended: ${error.message}")
        } finally {
            DmrLog.i("airplay mirror socket closed from $remote")
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            if (videoSocket == socket) videoSocket = null
        }
    }

    fun onAudioDatagram(packet: ByteArray) {
        audioLogs++
        if (audioLogs <= 3 || audioLogs % 200 == 0) {
            DmrLog.i("airplay audio datagram bytes=${packet.size} hex=${AirPlayKeys.hexPrefix(packet, 8)}")
        }
        decode.post { onAudio(packet) }
    }

    fun stop(reason: String) {
        if (!alive) return
        if (reason == "service") alive = false
        sessionToken++
        timingRunning = false
        timingThread?.interrupt()
        timingThread = null
        opened = false
        DmrLog.i("airplay mirror stop reason=$reason")
        try {
            videoSocket?.close()
        } catch (_: Throwable) {
        }
        decode.post {
            releaseVideoCodec()
            releaseAudioCodec()
            videoCipher = null
            sps = null
            pps = null
            pendingPrefix = null
            latestFrame = null
        }
        MirrorActivity.finishActive()
    }

    fun release() {
        stop("service")
        decodeThread.quitSafely()
        if (active == this) active = null
    }

    private fun onVideo(header: MirrorPackets.Header, payload: ByteArray) {
        if (!alive) return
        when (header.type) {
            0 -> onEncryptedVideo(payload)
            1 -> onCodec(header, payload)
            2, 5 -> Unit
            else -> DmrLog.i("airplay mirror unused type=${header.type}")
        }
    }

    private fun onEncryptedVideo(payload: ByteArray) {
        val cipher = videoCipher
        if (cipher == null) {
            DmrLog.i("airplay mirror video packet before AES key")
            return
        }
        val plain = try {
            cipher.decrypt(payload)
        } catch (error: Throwable) {
            DmrLog.i("airplay mirror decrypt failed: ${error.message}")
            return
        }
        val annex = MirrorPackets.toAnnexB(plain)
        if (annex == null) {
            DmrLog.i(
                "airplay mirror nal rejected bytes=${plain.size} head=${AirPlayKeys.hexPrefix(plain, 8)}",
            )
            return
        }
        val prefix = pendingPrefix
        val frame = if (prefix != null) prefix + annex else annex
        pendingPrefix = null
        latestFrame = frame
        queueVideo(frame)
    }

    private fun onCodec(header: MirrorPackets.Header, payload: ByteArray) {
        if (header.width >= 16) width = header.width
        if (header.height >= 16) height = header.height
        val sets = MirrorPackets.extractH264(payload)
        if (sets == null) {
            DmrLog.i("airplay mirror codec packet is not H.264 bytes=${payload.size}")
            return
        }
        sps = sets.first
        pps = sets.second
        pendingPrefix = MirrorPackets.annex(sets.first) + MirrorPackets.annex(sets.second)
        DmrLog.i("airplay mirror sps=${sets.first.size} pps=${sets.second.size} ${width}x$height")
        releaseVideoCodec()
        startVideoCodec()
    }

    private fun startVideoCodec() {
        if (!alive || videoCodec != null) return
        val target = surface ?: return
        val spsBytes = sps ?: return
        val ppsBytes = pps ?: return
        if (!target.isValid) return
        try {
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width.coerceAtLeast(16),
                height.coerceAtLeast(16),
            )
            format.setByteBuffer("csd-0", ByteBuffer.wrap(MirrorPackets.annex(spsBytes)))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(MirrorPackets.annex(ppsBytes)))
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2_000_000)
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            decoder.configure(format, target, null, 0)
            decoder.start()
            videoCodec = decoder
            DmrLog.i("airplay mirror H.264 decoder started ${width}x$height")
            latestFrame?.let { queueVideo(it) }
        } catch (error: Throwable) {
            DmrLog.i("airplay mirror decoder failed: ${error.javaClass.simpleName} ${error.message}")
            statusText = "镜像解码失败"
            statusView?.post { statusView?.text = statusText }
        }
    }

    private fun queueVideo(frame: ByteArray) {
        val decoder = videoCodec ?: return
        try {
            val index = decoder.dequeueInputBuffer(5_000)
            if (index < 0) return
            val buffer = decoder.getInputBuffer(index) ?: return
            buffer.clear()
            if (frame.size > buffer.capacity()) {
                DmrLog.i("airplay mirror frame ${frame.size} exceeds input ${buffer.capacity()}")
                return
            }
            buffer.put(frame)
            decoder.queueInputBuffer(index, 0, frame.size, System.nanoTime() / 1000L, 0)
            drain(decoder, video = true)
        } catch (error: Throwable) {
            DmrLog.i("airplay mirror queue failed: ${error.message}")
        }
    }

    private fun startAudioCodec() {
        if (audioFailed || audioCodec != null) return
        if (audioCt != 4 && audioCt != 8) {
            DmrLog.i("airplay audio ct=$audioCt left on the UDP port (AAC-ELD is ct 8, AAC-LC is ct 4)")
            return
        }
        try {
            val sampleRate = 44_100
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 2)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096)
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (audioCt == 8) {
                if (Build.VERSION.SDK_INT < 28) {
                    DmrLog.i("airplay audio AAC-ELD needs API 28; video continues")
                    return
                }
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
            } else {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder.configure(format, null, null, 0)
            decoder.start()
            audioCodec = decoder
            DmrLog.i("airplay audio decoder started ct=$audioCt")
        } catch (error: Throwable) {
            audioFailed = true
            DmrLog.i("airplay audio decoder failed: ${error.javaClass.simpleName} ${error.message}")
        }
    }

    private fun onAudio(packet: ByteArray) {
        if (!alive || audioFailed) return
        val key = audioKey ?: return
        val iv = audioIv ?: return
        if (packet.size < 12 || ((packet[0].toInt() ushr 6) and 3) != 2) return
        val header = rtpHeaderLength(packet) ?: return
        if (header >= packet.size) return
        val payload = packet.copyOfRange(header, packet.size)
        val plain = decryptCbc(key, iv, payload) ?: return
        val decoder = audioCodec ?: return
        try {
            val index = decoder.dequeueInputBuffer(2_000)
            if (index < 0) return
            val buffer = decoder.getInputBuffer(index) ?: return
            buffer.clear()
            if (plain.size > buffer.capacity()) return
            buffer.put(plain)
            decoder.queueInputBuffer(index, 0, plain.size, System.nanoTime() / 1000L, 0)
            drain(decoder, video = false)
        } catch (error: Throwable) {
            audioFailed = true
            DmrLog.i("airplay audio queue failed: ${error.message}")
        }
    }

    private fun drain(decoder: MediaCodec, video: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                decoder.releaseOutputBuffer(index, video)
                if (video && !presented && info.size > 0) {
                    presented = true
                    DmrLog.i("airplay mirror first frame")
                    onPresented?.invoke()
                }
            } else {
                break
            }
        }
    }

    private fun startTiming(remoteIp: String, clientPort: Int) {
        synchronized(gate) {
            if (clientPort <= 0) {
                DmrLog.i("airplay timing client port missing; still listening locally")
                return
            }
            if (timingThread?.isAlive == true && timingRunning) return
            timingRunning = true
            val token = sessionToken
            val worker = Thread({
                DmrLog.i("airplay timing requests to $remoteIp:$clientPort")
                while (alive && token == sessionToken && !Thread.currentThread().isInterrupted) {
                    try {
                        val body = MirrorTiming.request(System.currentTimeMillis() * 1_000_000L)
                        val packet = DatagramPacket(body, body.size, InetAddress.getByName(remoteIp), clientPort)
                        timingSocket?.send(packet)
                        DmrLog.i("airplay timing request sent to $remoteIp:$clientPort")
                    } catch (error: Throwable) {
                        if (timingRunning) DmrLog.i("airplay timing send failed: ${error.message}")
                    }
                    try {
                        Thread.sleep(3_000)
                    } catch (_: InterruptedException) {
                        break
                    }
                }
            }, "airplay-ntp")
            worker.isDaemon = true
            timingThread = worker
            worker.start()
        }
    }

    private fun releaseVideoCodec() {
        val decoder = videoCodec ?: return
        videoCodec = null
        presented = false
        try {
            decoder.stop()
        } catch (_: Throwable) {
        }
        try {
            decoder.release()
        } catch (_: Throwable) {
        }
    }

    private fun releaseAudioCodec() {
        val decoder = audioCodec ?: return
        audioCodec = null
        try {
            decoder.stop()
        } catch (_: Throwable) {
        }
        try {
            decoder.release()
        } catch (_: Throwable) {
        }
    }

    private fun rtpHeaderLength(packet: ByteArray): Int? {
        val cc = packet[0].toInt() and 0x0F
        var length = 12 + cc * 4
        if (length > packet.size) return null
        if ((packet[0].toInt() and 0x10) != 0) {
            if (length + 4 > packet.size) return null
            val words = ((packet[length + 2].toInt() and 0xFF) shl 8) or (packet[length + 3].toInt() and 0xFF)
            length += 4 + words * 4
        }
        return if (length <= packet.size) length else null
    }

    private fun decryptCbc(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? {
        val length = data.size - data.size % 16
        if (length <= 0) return null
        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.copyOf(16), "AES"), IvParameterSpec(iv.copyOf(16)))
            cipher.doFinal(data, 0, length)
        } catch (error: Throwable) {
            DmrLog.i("airplay audio decrypt failed: ${error.message}")
            null
        }
    }

    private fun readFully(input: java.io.InputStream, size: Int): ByteArray? {
        val out = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(out, offset, size - offset)
            if (count < 0) return null
            offset += count
        }
        return out
    }

    private class ContinualCtr(key: ByteArray, iv: ByteArray) {
        private val cipher = SICBlockCipher(AESEngine())

        init {
            cipher.init(true, ParametersWithIV(KeyParameter(key.copyOf(16)), iv.copyOf(16)))
        }

        fun decrypt(input: ByteArray): ByteArray {
            if (input.isEmpty()) return input
            val out = ByteArray(input.size)
            cipher.processBytes(input, 0, input.size, out, 0)
            return out
        }
    }

    companion object {
        @Volatile
        var active: MirrorSession? = null
    }
}
