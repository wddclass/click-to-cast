package com.wddclass.clicktocast.airplay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import com.wddclass.clicktocast.dlna.DmrLog
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Decrypts type 96 RTP, holds a few frames, decodes AAC-ELD or AAC-LC, and writes PCM.
 * ALAC is logged and left alone. Failure here does not stop the picture.
 */
internal class MirrorAudioPlayer(
    context: Context,
    private val alive: () -> Boolean,
    private val sessionToken: () -> Int,
    private val videoPresentedAt: () -> Long,
) {
    private val appContext = context.applicationContext
    private val incoming = ArrayDeque<ByteArray>()
    private val gate = Any()
    private var worker: Thread? = null
    private var workerToken = 0
    private var owner = 0
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null
    private var focusRequest: Any? = null
    private var codecFailed = false
    private var notedFormat = false
    private var ct = 0
    private var key = ByteArray(0)
    private var iv = ByteArray(0)
    @Volatile private var gain = 1f
    @Volatile private var flushRequested = false
    @Volatile private var syncRtp = 0L
    @Volatile private var syncAt = 0L
    @Volatile private var syncGen = 0
    private var loggedDatagrams = 0
    private var loggedUnknown = 0

    fun start(token: Int, rawKey: ByteArray, rawIv: ByteArray?, compression: Int) {
        key = rawKey.copyOf(16)
        iv = rawIv?.copyOf(16) ?: ByteArray(0)
        ct = compression
        gain = 1f
        flushRequested = false
        syncGen = 0
        codecFailed = false
        notedFormat = false
        synchronized(gate) {
            owner = token
            if (worker?.isAlive == true && workerToken == token) return
            workerToken = token
            worker?.interrupt()
            val thread = Thread({ run(token) }, "airplay-audio")
            thread.isDaemon = true
            worker = thread
            thread.start()
        }
        val kind = when (compression) {
            8 -> "AAC-ELD"
            4 -> "AAC-LC"
            2 -> "ALAC"
            else -> "ct=$compression"
        }
        DmrLog.i(
            "airplay audio arm $kind ct=$compression iv=${rawIv?.size ?: 0} " +
                "keyPrefix=${AirPlayKeys.hex(key).take(8)}",
        )
    }

    fun offer(packet: ByteArray) {
        loggedDatagrams++
        if (loggedDatagrams <= 4 || loggedDatagrams % 200 == 0) {
            DmrLog.i(
                "airplay audio datagram bytes=${packet.size} seq=${if (packet.size >= 4) MirrorAudio.sequence(packet) else -1} " +
                    "hex=${AirPlayKeys.hexPrefix(packet, 8)}",
            )
        }
        synchronized(gate) {
            if (incoming.size >= 48) incoming.removeFirst()
            incoming.addLast(packet)
        }
    }

    fun control(packet: ByteArray) {
        val resent = MirrorAudio.resent(packet)
        if (resent != null) {
            DmrLog.i("airplay audio resent bytes=${resent.size}")
            offer(resent)
            return
        }
        val sync = MirrorAudio.sync(packet)
        if (sync != null) {
            syncRtp = sync.first
            syncAt = System.nanoTime()
            syncGen++
            if (syncGen <= 3 || syncGen % 30 == 0) {
                DmrLog.i("airplay audio sync rtp=${sync.first} ntp=${sync.second}")
            }
            return
        }
        loggedUnknown++
        if (loggedUnknown <= 3) {
            val type = if (packet.size >= 2) packet[1].toInt() and 0x7F else -1
            DmrLog.i("airplay audio control type=$type bytes=${packet.size}")
        }
    }

    fun setVolumeDb(db: Float) {
        val next = MirrorAudio.linearGain(db)
        gain = next
        DmrLog.i("airplay audio volume ${"%.3f".format(db)}dB gain=${"%.3f".format(next)}")
        try {
            track?.setVolume(next)
        } catch (error: Throwable) {
            DmrLog.i("airplay audio volume failed: ${error.message}")
        }
    }

    fun flush() {
        DmrLog.i("airplay audio flush")
        flushRequested = true
        synchronized(gate) { incoming.clear() }
    }

    fun stop() {
        val thread = synchronized(gate) {
            owner = 0
            worker
        }
        thread?.interrupt()
        releasePlayback()
    }

    private fun run(token: Int) {
        val jitter = AudioJitter()
        var anchorRtp = 0L
        var anchorAt = 0L
        var anchored = false
        var seenSync = 0
        var played = 0
        var skipped = 0
        try {
            while (alive() && token == sessionToken() && token == owner) {
                if (flushRequested) {
                    flushRequested = false
                    jitter.clear()
                    anchored = false
                    seenSync = syncGen
                    try {
                        track?.pause()
                        track?.flush()
                        track?.play()
                    } catch (_: Throwable) {
                    }
                }
                drainIncoming(jitter, token)
                val gen = syncGen
                if (gen != seenSync && syncAt != 0L) {
                    val rtp = syncRtp
                    val at = syncAt
                    seenSync = gen
                    if (!anchored) {
                        anchorRtp = rtp
                        anchorAt = at
                        anchored = true
                        DmrLog.i("airplay audio sync rtp=$rtp")
                    } else {
                        val driftMs = (anchorAt + MirrorAudio.leadNs(rtp, anchorRtp) - at) / 1_000_000L
                        if (abs(driftMs) > 80L) {
                            anchorAt -= driftMs * 1_000_000L
                            DmrLog.i("airplay audio sync drift ${driftMs}ms rtp=$rtp")
                        }
                    }
                }
                val frame = jitter.dequeue(jitter.pending() >= 8)
                if (frame == null) {
                    sleepBriefly()
                    continue
                }
                if (!anchored) {
                    val now = System.nanoTime()
                    val picture = videoPresentedAt()
                    val delay = if (picture > 0L && now - picture < 2_000_000_000L) {
                        MirrorAudio.VIDEO_CUSHION_NS
                    } else {
                        MirrorAudio.START_DELAY_NS
                    }
                    anchorRtp = frame.rtp
                    anchorAt = now + delay
                    anchored = true
                    DmrLog.i("airplay audio anchor rtp=${frame.rtp} delayMs=${delay / 1_000_000}")
                }
                val due = anchorAt + MirrorAudio.leadNs(frame.rtp, anchorRtp)
                waitUntil(due, jitter, token)
                val lateMs = (System.nanoTime() - due) / 1_000_000L
                if (lateMs > 120L) {
                    skipped++
                    if (skipped <= 4 || skipped % 50 == 0) {
                        DmrLog.i("airplay audio drop late seq=${frame.seq} by ${lateMs}ms")
                    }
                    continue
                }
                if (!play(frame, token)) {
                    sleepBriefly()
                    continue
                }
                played++
                if (played == 1 || played % 200 == 0) {
                    DmrLog.i("airplay audio playing frames=$played seq=${frame.seq} rtp=${frame.rtp}")
                }
            }
        } catch (_: InterruptedException) {
        } catch (error: Throwable) {
            DmrLog.i("airplay audio ended: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            if (token == owner || owner == 0) releasePlayback()
        }
    }

    private fun drainIncoming(jitter: AudioJitter, token: Int) {
        if (ct != 4 && ct != 8) {
            if (pollPacket() == null) return
            synchronized(gate) { incoming.clear() }
            if (!notedFormat) {
                notedFormat = true
                val message = if (ct == 2) "airplay audio ALAC ct=2 left undecoded" else "airplay audio ct=$ct left undecoded"
                DmrLog.i(message)
            }
            return
        }
        if (iv.size < 16) {
            if (pollPacket() == null) return
            synchronized(gate) { incoming.clear() }
            if (!notedFormat) {
                notedFormat = true
                DmrLog.i("airplay audio missing iv")
            }
            return
        }
        while (alive() && token == sessionToken()) {
            val packet = pollPacket() ?: return
            val plain = try {
                MirrorAudio.decrypt(packet, key, iv)
            } catch (error: Throwable) {
                DmrLog.i("airplay audio decrypt failed: ${error.message}")
                null
            } ?: continue
            if (MirrorAudio.isNoData(plain)) continue
            if (packet.size < 8) continue
            jitter.enqueue(MirrorAudio.sequence(packet), MirrorAudio.timestamp(packet), plain)
        }
    }

    private fun pollPacket(): ByteArray? = synchronized(gate) {
        if (incoming.isEmpty()) null else incoming.removeFirst()
    }

    private fun waitUntil(due: Long, jitter: AudioJitter, token: Int) {
        while (alive() && token == sessionToken() && token == owner) {
            drainIncoming(jitter, token)
            val earlyMs = (due - System.nanoTime()) / 1_000_000L
            if (earlyMs <= 2L) return
            Thread.sleep(earlyMs.coerceAtMost(20L))
        }
    }

    private fun play(frame: AudioJitter.Frame, token: Int): Boolean {
        val decoder = ensureCodec(token) ?: return false
        return try {
            val index = decoder.dequeueInputBuffer(8_000)
            if (index < 0) return false
            val input = decoder.getInputBuffer(index) ?: return false
            input.clear()
            if (frame.payload.size > input.capacity()) {
                DmrLog.i("airplay audio frame ${frame.payload.size} exceeds ${input.capacity()}")
                return false
            }
            input.put(frame.payload)
            val ptsUs = frame.rtp * 1_000_000L / MirrorAudio.SAMPLE_RATE
            decoder.queueInputBuffer(index, 0, frame.payload.size, ptsUs, 0)
            drainDecoder(decoder, token)
            true
        } catch (error: Throwable) {
            DmrLog.i("airplay audio queue failed: ${error.javaClass.simpleName}: ${error.message}")
            false
        }
    }

    private fun drainDecoder(decoder: MediaCodec, token: Int) {
        val info = MediaCodec.BufferInfo()
        var polls = 0
        while (alive() && token == sessionToken() && polls++ < 4) {
            val index = decoder.dequeueOutputBuffer(info, 8_000)
            when {
                index >= 0 -> {
                    val output = decoder.getOutputBuffer(index)
                    if (output != null && info.size > 0) writePcm(output, info.offset, info.size)
                    decoder.releaseOutputBuffer(index, false)
                    return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                    DmrLog.i("airplay audio output format ${decoder.outputFormat}")
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                else -> return
            }
        }
    }

    private fun writePcm(buffer: ByteBuffer, offset: Int, size: Int) {
        val pcm = ByteArray(size)
        buffer.position(offset)
        buffer.limit(offset + size)
        buffer.get(pcm)
        val speaker = ensureTrack() ?: return
        try {
            speaker.setVolume(gain)
            val wrote = speaker.write(pcm, 0, pcm.size)
            if (wrote < 0) DmrLog.i("airplay audio track write $wrote")
        } catch (error: Throwable) {
            DmrLog.i("airplay audio track write failed: ${error.message}")
        }
    }

    private fun ensureCodec(token: Int): MediaCodec? {
        codec?.let { return it }
        if (codecFailed || (ct != 4 && ct != 8)) return null
        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, MirrorAudio.SAMPLE_RATE, 2)
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4_096)
            format.setInteger(MediaFormat.KEY_IS_ADTS, 0)
            if (ct == 8) {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(MirrorAudio.ELD_CSD))
            } else {
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(MirrorAudio.LC_CSD))
            }
            val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder.configure(format, null, null, 0)
            decoder.start()
            synchronized(gate) {
                if (token != sessionToken() || token != owner) {
                    decoder.release()
                    return null
                }
                codec = decoder
            }
            DmrLog.i(
                "airplay audio decoder started ${if (ct == 8) "AAC-ELD" else "AAC-LC"} " +
                    "${MirrorAudio.SAMPLE_RATE} stereo csd=${AirPlayKeys.hex(if (ct == 8) MirrorAudio.ELD_CSD else MirrorAudio.LC_CSD)}",
            )
            decoder
        } catch (error: Throwable) {
            codecFailed = true
            DmrLog.i("airplay audio decoder failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun ensureTrack(): AudioTrack? {
        track?.let { return it }
        return try {
            val min = AudioTrack.getMinBufferSize(
                MirrorAudio.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val size = maxOf(min, MirrorAudio.SAMPLE_RATE * 2 * 2 / 5)
            val speaker = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(MirrorAudio.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(size)
                .build()
            speaker.setVolume(gain)
            speaker.play()
            track = speaker
            requestFocus()
            DmrLog.i("airplay audio track started buffer=$size")
            speaker
        } catch (error: Throwable) {
            DmrLog.i("airplay audio track failed: ${error.javaClass.simpleName}: ${error.message}")
            null
        }
    }

    private fun requestFocus() {
        val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build(),
                    )
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (error: Throwable) {
            DmrLog.i("airplay audio focus failed: ${error.message}")
        }
    }

    private fun releasePlayback() {
        val decoder: MediaCodec?
        val speaker: AudioTrack?
        val request: Any?
        synchronized(gate) {
            decoder = codec
            codec = null
            speaker = track
            track = null
            request = focusRequest
            focusRequest = null
            incoming.clear()
        }
        try {
            decoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            decoder?.release()
        } catch (_: Throwable) {
        }
        try {
            speaker?.pause()
            speaker?.flush()
            speaker?.release()
        } catch (_: Throwable) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focus = request as? AudioFocusRequest ?: return
            try {
                val manager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                manager?.abandonAudioFocusRequest(focus)
            } catch (_: Throwable) {
            }
        }
    }

    private fun sleepBriefly() {
        try {
            Thread.sleep(2)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        }
    }
}
