package com.wddclass.clicktocast.airplay

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.View
import android.widget.TextView
import com.wddclass.clicktocast.dlna.DmrLog
import com.wddclass.clicktocast.ui.MirrorActivity
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.nio.ByteBuffer

/**
 * One AirPlay screen mirror: FairPlay-unwrapped AES-CTR video into MediaCodec,
 * NTP timing towards the phone, and AAC-ELD / AAC-LC mirror audio.
 */
class MirrorSession(
    context: Context,
    private val openActivity: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioPlayer = MirrorAudioPlayer(appContext, { alive }, { sessionToken }, { presentedAt })
    private val decodeThread = HandlerThread("airplay-decode")
    private val decode: Handler
    private val gate = Any()

    @Volatile private var alive = true
    @Volatile private var sessionToken = 0
    @Volatile private var statusText = "正在接收屏幕镜像"
    @Volatile private var statusView: TextView? = null
    @Volatile var onPresented: (() -> Unit)? = null
    @Volatile var onSized: ((Int, Int) -> Unit)? = null

    private var timingSocket: DatagramSocket? = null
    private var timingRunning = false
    private var timingThread: Thread? = null
    private var timingLogs = 0
    private var videoLogs = 0
    private var opened = false
    private var presented = false
    @Volatile private var presentedAt = 0L

    @Volatile private var videoCipher: MirrorCtr? = null
    private var videoCodec: MediaCodec? = null
    private var surface: Surface? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    @Volatile private var width = 1920
    @Volatile private var height = 1080
    @Volatile private var clientName = ""
    @Volatile private var peerIp = ""
    @Volatile private var controlConnection = 0L
    @Volatile private var openedAt = 0L
    @Volatile private var lastVideoAt = 0L
    @Volatile private var lastFeedbackAt = 0L
    @Volatile private var lastTimingAt = 0L
    @Volatile private var bytesPerSecond = 0L
    private var sessionClosed = false
    private var byteWindow = 0L
    private var windowStart = 0L
    private var pendingPrefix: ByteArray? = null
    private val pendingFrames = ArrayDeque<PendingFrame>()
    private var nalRejects = 0
    private var fedFrames = 0
    private var outputPolls = 0
    private var outputLogs = 0
    private var reportLogs = 0
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
        videoCipher = MirrorCtr(key, iv)
        synchronized(gate) {
            sessionClosed = false
            openedAt = System.currentTimeMillis()
            lastVideoAt = 0L
            lastFeedbackAt = 0L
            lastTimingAt = 0L
            byteWindow = 0L
            windowStart = 0L
            bytesPerSecond = 0L
        }
        if (remoteIp.isNotBlank()) peerIp = remoteIp
        startTiming(remoteIp, clientTimingPort)
        show()
        startWatch()
    }

    fun noteClientName(name: String) {
        val shown = name.trim()
        if (shown.isEmpty()) return
        clientName = shown
        DmrLog.i("airplay mirror client name=$shown")
    }

    fun noteControl(connectionId: Long, remoteIp: String) {
        if (remoteIp.isNotBlank()) peerIp = remoteIp
        if (connectionId == 0L) return
        synchronized(gate) {
            val next = MirrorEnd.nextControl(controlConnection, connectionId, sessionClosed)
            if (next != controlConnection) {
                controlConnection = next
                DmrLog.i("airplay mirror control connection id=$next from $remoteIp")
            } else if (connectionId != controlConnection) {
                DmrLog.i(
                    "airplay mirror keeps control id=$controlConnection " +
                        "(ignored id=$connectionId from $remoteIp)",
                )
            }
        }
    }

    fun noteFeedback() {
        lastFeedbackAt = System.currentTimeMillis()
    }

    fun controlClosed(connectionId: Long, remoteIp: String, reason: String) {
        if (reason == "timeout" && mediaFresh()) {
            DmrLog.i("airplay rtsp timeout id=$connectionId from $remoteIp ignored; mirror still receiving")
            return
        }
        val open = opened || videoSocket != null
        if (!MirrorEnd.controlClosed(controlConnection, connectionId, open)) {
            DmrLog.i(
                "airplay rtsp closed id=$connectionId from $remoteIp reason=$reason " +
                    "ignored (control=$controlConnection open=$open)",
            )
            return
        }
        stop("rtsp $reason from $remoteIp")
    }

    fun mdnsGoodbye(remoteIp: String) {
        if (!opened) return
        if (!MirrorEnd.samePeer(peerIp, remoteIp)) {
            DmrLog.i("airplay mdns goodbye from $remoteIp ignored (peer=$peerIp)")
            return
        }
        stop("mdns goodbye from $remoteIp")
    }

    fun chrome(): Triple<String, String, Long> {
        val name = clientName.ifBlank { "iPhone" }
        val size = if (width >= 16 && height >= 16) "${width}×${height}" else ""
        return Triple(name, size, bytesPerSecond)
    }

    /** NTP toward the phone. The mirror view stays closed until a type 110 stream arrives. */
    fun noteTiming(remoteIp: String, clientTimingPort: Int) {
        if (clientTimingPort <= 0) return
        DmrLog.i("airplay timing from session SETUP to $remoteIp:$clientTimingPort")
        startTiming(remoteIp, clientTimingPort)
    }

    fun armAudio(rawKey: ByteArray, ecdhSecret: ByteArray?, eiv: ByteArray?, ct: Int) {
        val mixed = MirrorKeys.mixWithEcdh(rawKey, ecdhSecret)
        audioPlayer.start(sessionToken, mixed, eiv, ct)
    }

    fun flushAudio() {
        audioPlayer.flush()
    }

    fun setVolumeDb(db: Float) {
        audioPlayer.setVolumeDb(db)
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
        lastTimingAt = System.currentTimeMillis()
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
        if (remote.isNotBlank() && remote != "?") peerIp = remote
        var why = "data eof"
        val token = sessionToken
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = 15_000
            val input = socket.getInputStream()
            var armedTimeout = false
            while (alive && token == sessionToken) {
                val headerBytes = try {
                    readFully(input, 128)
                } catch (timeout: java.net.SocketTimeoutException) {
                    if (feedbackFresh()) {
                        DmrLog.i("airplay mirror data quiet from $remote; feedback still live")
                        continue
                    }
                    why = "data idle"
                    DmrLog.i("airplay mirror data idle from $remote")
                    break
                }
                if (headerBytes == null) {
                    why = "data eof"
                    break
                }
                val header = MirrorPackets.header(headerBytes)
                if (header == null) {
                    why = "data bad header"
                    break
                }
                if (header.payloadSize < 0 || header.payloadSize > 8_000_000) {
                    DmrLog.i("airplay mirror bad payload ${header.payloadSize}")
                    why = "data bad payload"
                    break
                }
                val payload = if (header.payloadSize == 0) {
                    ByteArray(0)
                } else {
                    readFully(input, header.payloadSize)
                }
                if (payload == null) {
                    why = "data eof"
                    break
                }
                noteBytes(128 + payload.size)
                if (!armedTimeout) {
                    armedTimeout = true
                    socket.soTimeout = 5_000
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
        } catch (timeout: java.net.SocketTimeoutException) {
            why = "data idle"
            DmrLog.i("airplay mirror data idle from $remote")
        } catch (error: Throwable) {
            val message = error.message.orEmpty()
            why = if (
                message.contains("reset", ignoreCase = true) ||
                message.contains("broken", ignoreCase = true) ||
                message.contains("abort", ignoreCase = true)
            ) {
                "data reset"
            } else {
                "data error"
            }
            if (alive) DmrLog.i("airplay mirror socket ended: ${error.javaClass.simpleName}: $message")
        } finally {
            DmrLog.i("airplay mirror socket closed from $remote why=$why")
            try {
                socket.close()
            } catch (_: Throwable) {
            }
            if (videoSocket == socket) videoSocket = null
            if (token == sessionToken) stop(why)
        }
    }

    private fun feedbackFresh(): Boolean {
        val seen = lastFeedbackAt
        return seen > 0L && System.currentTimeMillis() - seen < MirrorEnd.IDLE_MS
    }

    private fun mediaFresh(): Boolean {
        val now = System.currentTimeMillis()
        val videoFresh = lastVideoAt > 0L && now - lastVideoAt < MirrorEnd.IDLE_MS
        return videoFresh || feedbackFresh()
    }

    private fun noteBytes(count: Int) {
        val now = System.currentTimeMillis()
        lastVideoAt = now
        byteWindow += count
        if (windowStart == 0L) windowStart = now
        val elapsed = now - windowStart
        if (elapsed >= 1_000L) {
            bytesPerSecond = byteWindow * 1_000L / elapsed
            byteWindow = 0L
            windowStart = now
        }
    }

    private fun startWatch() {
        val token = sessionToken
        Thread({
            while (alive && token == sessionToken) {
                try {
                    Thread.sleep(1_000L)
                } catch (_: InterruptedException) {
                    break
                }
                if (token != sessionToken || !opened) continue
                val why = MirrorEnd.reason(
                    System.currentTimeMillis(),
                    openedAt,
                    lastVideoAt,
                    lastFeedbackAt,
                    lastTimingAt,
                )
                if (why != null) {
                    stop(why)
                    break
                }
            }
        }, "airplay-mirror-watch").apply {
            isDaemon = true
            start()
        }
    }

    fun onAudioDatagram(packet: ByteArray) {
        audioPlayer.offer(packet)
    }

    fun onAudioControl(packet: ByteArray) {
        audioPlayer.control(packet)
    }

    fun stop(reason: String) {
        if (!alive && reason != "service") return
        synchronized(gate) {
            if (reason == "service") alive = false
            if (sessionClosed && reason != "service") return
            sessionClosed = true
            sessionToken++
            controlConnection = 0L
        }
        timingRunning = false
        timingThread?.interrupt()
        timingThread = null
        audioPlayer.stop()
        opened = false
        DmrLog.i("airplay mirror stop reason=$reason")
        try {
            videoSocket?.close()
        } catch (_: Throwable) {
        }
        decode.post {
            releaseVideoCodec()
            videoCipher = null
            sps = null
            pps = null
            pendingPrefix = null
            pendingFrames.clear()
            nalRejects = 0
            fedFrames = 0
        }
        if (reason != "service") MirrorActivity.finishActive()
    }

    fun release() {
        stop("service")
        decodeThread.quitSafely()
        if (active == this) active = null
    }

    private fun onVideo(header: MirrorPackets.Header, payload: ByteArray) {
        if (!alive) return
        when (header.type) {
            0 -> onEncryptedVideo(header, payload)
            1 -> onCodec(header, payload)
            // UxPlay: byte 4 == 5 is an unencrypted streaming report, once a second.
            // It is not HEVC and must not be run through the video AES-CTR counter.
            5 -> {
                reportLogs++
                if (reportLogs <= 3 || reportLogs % 30 == 0) {
                    DmrLog.i("airplay mirror type 5 streaming report bytes=${payload.size} (not video)")
                }
            }
            2 -> Unit
            else -> DmrLog.i("airplay mirror unused type=${header.type}")
        }
    }

    private fun onEncryptedVideo(header: MirrorPackets.Header, payload: ByteArray) {
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
            nalRejects++
            DmrLog.i(
                "airplay mirror nal rejected ${MirrorPackets.annexProblem(plain)} " +
                    "bytes=${plain.size} option=${header.option} head=${AirPlayKeys.hexPrefix(plain, 16)}",
            )
            if (nalRejects == 3) showStatus("镜像画面解不开")
            return
        }
        nalRejects = 0
        val prefix = pendingPrefix
        val bytes = if (prefix != null) prefix + annex else annex
        pendingPrefix = null
        val idr = header.option == 0x10 || MirrorPackets.nalTypes(annex).split(',').any { it == "5" }
        if (fedFrames < 8 || idr) {
            DmrLog.i(
                "airplay mirror decrypted bytes=${plain.size} nals=${MirrorPackets.nalTypes(bytes)} " +
                    "idr=$idr option=${header.option} prepended=${prefix != null}",
            )
        }
        pendingFrames.addLast(PendingFrame(bytes, idr))
        while (pendingFrames.size > 60) pendingFrames.removeFirst()
        flushPending()
    }

    private fun onCodec(header: MirrorPackets.Header, payload: ByteArray) {
        if (header.width >= 16) width = header.width
        if (header.height >= 16) height = header.height
        val sets = MirrorPackets.extractH264(payload)
        if (sets == null) {
            val marker = if (payload.size >= 8) AirPlayKeys.hexPrefix(payload, 8) else ""
            DmrLog.i("airplay mirror codec packet is not H.264 bytes=${payload.size} head=$marker")
            showStatus("这份镜像不是 H.264")
            return
        }
        val parsed = H264Sps.displaySize(sets.first)
        if (parsed != null) {
            DmrLog.i(
                "airplay mirror sps size ${parsed.first}x${parsed.second} " +
                    "header ${header.width}x${header.height} display ${header.displayWidth}x${header.displayHeight}",
            )
            width = parsed.first
            height = parsed.second
        }
        sps = sets.first
        pps = sets.second
        pendingFrames.clear()
        pendingPrefix = MirrorPackets.annex(sets.first) + MirrorPackets.annex(sets.second)
        DmrLog.i(
            "airplay mirror sps=${sets.first.size} pps=${sets.second.size} ${width}x$height " +
                "spsHex=${AirPlayKeys.hexPrefix(sets.first, 12)}",
        )
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
            outputPolls = 0
            DmrLog.i("airplay mirror H.264 decoder started ${width}x$height queued=${pendingFrames.size}")
            onSized?.invoke(width, height)
            flushPending()
            drain(decoder, video = true)
            scheduleOutput()
        } catch (error: Throwable) {
            val extra = if (error is MediaCodec.CodecException) " diagnostic=${error.diagnosticInfo}" else ""
            DmrLog.i("airplay mirror decoder failed: ${error.javaClass.simpleName} ${error.message}$extra")
            showStatus("镜像解码失败")
        }
    }

    private fun flushPending() {
        val decoder = videoCodec ?: return
        var guard = 0
        while (pendingFrames.isNotEmpty() && guard++ < 12) {
            val frame = pendingFrames.first()
            if (!enqueue(decoder, frame)) return
            pendingFrames.removeFirst()
            drain(decoder, video = true)
        }
    }

    private fun enqueue(decoder: MediaCodec, frame: PendingFrame): Boolean {
        return try {
            val index = decoder.dequeueInputBuffer(20_000)
            if (index < 0) {
                DmrLog.i("airplay mirror input not ready bytes=${frame.bytes.size} idr=${frame.idr}")
                false
            } else {
                val buffer = decoder.getInputBuffer(index)
                if (buffer == null) {
                    decoder.queueInputBuffer(index, 0, 0, 0, 0)
                    false
                } else if (frame.bytes.size > buffer.capacity()) {
                    DmrLog.i("airplay mirror frame ${frame.bytes.size} exceeds input ${buffer.capacity()}")
                    decoder.queueInputBuffer(index, 0, 0, 0, 0)
                    true
                } else {
                    buffer.clear()
                    buffer.put(frame.bytes)
                    val flags = if (frame.idr) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    decoder.queueInputBuffer(index, 0, frame.bytes.size, fedFrames * 33_333L, flags)
                    fedFrames++
                    if (fedFrames <= 8) {
                        DmrLog.i(
                            "airplay mirror queued bytes=${frame.bytes.size} flags=$flags fed=$fedFrames",
                        )
                    }
                    true
                }
            }
        } catch (error: Throwable) {
            val extra = if (error is MediaCodec.CodecException) " diagnostic=${error.diagnosticInfo}" else ""
            DmrLog.i("airplay mirror queue failed: ${error.javaClass.simpleName} ${error.message}$extra")
            showStatus("镜像解码失败")
            false
        }
    }

    private fun scheduleOutput() {
        decode.postDelayed({
            if (!alive || presented) return@postDelayed
            val decoder = videoCodec ?: return@postDelayed
            drain(decoder, video = true)
            flushPending()
            outputPolls++
            if (!presented && outputPolls < 50) {
                scheduleOutput()
            } else if (!presented && fedFrames > 0) {
                DmrLog.i("airplay mirror no output after $outputPolls polls fed=$fedFrames")
                showStatus("镜像没有画面")
            }
        }, 40)
    }

    private fun showStatus(message: String) {
        statusText = message
        DmrLog.i("airplay mirror status: $message")
        statusView?.post {
            statusView?.visibility = View.VISIBLE
            statusView?.text = message
        }
    }

    private fun drain(decoder: MediaCodec, video: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (alive) {
            val index = try {
                decoder.dequeueOutputBuffer(info, if (video) 8_000 else 0)
            } catch (error: Throwable) {
                val extra = if (error is MediaCodec.CodecException) " diagnostic=${error.diagnosticInfo}" else ""
                DmrLog.i("airplay mirror dequeue failed: ${error.javaClass.simpleName} ${error.message}$extra")
                if (video) showStatus("镜像解码失败")
                return
            }
            when {
                index >= 0 -> {
                    val render = video && info.size > 0
                    decoder.releaseOutputBuffer(index, render)
                    outputLogs++
                    if (video && (outputLogs <= 6 || info.size == 0)) {
                        DmrLog.i(
                            "airplay mirror output index=$index size=${info.size} flags=${info.flags} render=$render",
                        )
                    }
                    if (render && !presented) {
                        presented = true
                        presentedAt = System.nanoTime()
                        DmrLog.i("airplay mirror first frame")
                        statusView?.post { statusView?.visibility = View.GONE }
                        onPresented?.invoke()
                    }
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    DmrLog.i("airplay mirror output format ${decoder.outputFormat}")
                }
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> {
                    DmrLog.i("airplay mirror dequeue index=$index")
                    return
                }
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

    private data class PendingFrame(val bytes: ByteArray, val idr: Boolean)

    companion object {
        @Volatile
        var active: MirrorSession? = null
    }
}
