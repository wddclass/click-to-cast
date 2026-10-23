package com.wddclass.clicktocast.airplay

import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.pow

/**
 * AirPlay mirror audio (UxPlay `raop_buffer.c` / `raop_rtp.c`).
 *
 * The RTP header is a fixed 12 bytes. AES-128-CBC uses the mixed FairPlay key
 * and the SETUP `eiv`, then the IV is reset so the next packet starts over.
 * The leftover bytes after the last full block are copied unchanged.
 */
internal object MirrorAudio {
    const val SAMPLE_RATE = 44_100
    const val ELD_SAMPLES = 480
    const val START_DELAY_NS = 200_000_000L
    const val VIDEO_CUSHION_NS = 60_000_000L
    val ELD_CSD = byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)
    val LC_CSD = byteArrayOf(0x12, 0x10)
    val NO_DATA = byteArrayOf(0x00, 0x68, 0x34, 0x00)

    private val volumePattern = Regex("""volume:\s*(-?\d+(?:\.\d+)?)""")

    fun decrypt(packet: ByteArray, key: ByteArray, iv: ByteArray): ByteArray? {
        if (packet.size < 12 || key.size < 16 || iv.size < 16) return null
        if ((packet[0].toInt() ushr 6) and 0x03 != 2) return null
        val payloadSize = packet.size - 12
        if (payloadSize == 0) return NO_DATA.copyOf()
        if (isNoData(packet, 12)) return NO_DATA.copyOf()
        val encrypted = payloadSize / 16 * 16
        val out = ByteArray(payloadSize)
        if (encrypted > 0) {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key.copyOf(16), "AES"),
                IvParameterSpec(iv.copyOf(16)),
            )
            val plain = cipher.doFinal(packet, 12, encrypted)
            System.arraycopy(plain, 0, out, 0, plain.size.coerceAtMost(encrypted))
        }
        if (encrypted < payloadSize) {
            System.arraycopy(packet, 12 + encrypted, out, encrypted, payloadSize - encrypted)
        }
        return out
    }

    fun isNoData(data: ByteArray, offset: Int = 0): Boolean {
        if (data.size - offset != 4) return false
        return data[offset] == NO_DATA[0] &&
            data[offset + 1] == NO_DATA[1] &&
            data[offset + 2] == NO_DATA[2] &&
            data[offset + 3] == NO_DATA[3]
    }

    fun sequence(packet: ByteArray): Int =
        ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)

    fun timestamp(packet: ByteArray): Long {
        var value = 0L
        for (index in 0 until 4) {
            value = (value shl 8) or (packet[4 + index].toInt() and 0xFF).toLong()
        }
        return value
    }

    /** Control packet 0x54: RTP timestamp at byte 4, remote NTP at byte 8. */
    fun sync(packet: ByteArray): Pair<Long, Long>? {
        if (packet.size < 20) return null
        if ((packet[1].toInt() and 0x7F) != 0x54) return null
        return timestamp(packet) to u64be(packet, 8)
    }

    /** Control packet 0x56 carries a resent RTP packet starting at byte 4. */
    fun resent(packet: ByteArray): ByteArray? {
        if (packet.size < 16) return null
        if ((packet[1].toInt() and 0x7F) != 0x56) return null
        return packet.copyOfRange(4, packet.size)
    }

    fun volumeDb(text: String): Float? {
        val match = volumePattern.find(text) ?: return null
        return match.groupValues[1].toFloatOrNull()?.coerceIn(-144f, 0f)
    }

    fun formatVolume(db: Float): String =
        String.format(Locale.US, "volume: %.6f\r\n", db.coerceIn(-144f, 0f))

    /** AirPlay volume is dB. -144 is silence, 0 is full scale. */
    fun linearGain(db: Float): Float {
        val clamped = db.coerceIn(-144f, 0f)
        if (clamped <= -144f) return 0f
        return 10.0.pow(clamped / 20.0).toFloat()
    }

    /** Nanoseconds from [anchorRtp] to [rtp] at 44100 Hz, wrapping like a uint32. */
    fun leadNs(rtp: Long, anchorRtp: Long): Long =
        (rtp - anchorRtp).toInt().toLong() * 1_000_000_000L / SAMPLE_RATE

    fun seqCmp(left: Int, right: Int): Int = ((left and 0xFFFF) - (right and 0xFFFF)).toShort().toInt()

    private fun u64be(packet: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = (value shl 8) or (packet[offset + index].toInt() and 0xFF).toLong()
        }
        return value
    }
}

/** Small reorder buffer. Duplicate sequence numbers (AAC-ELD is sent three times) are dropped. */
internal class AudioJitter(private val limit: Int = 24) {
    class Frame(val seq: Int, val rtp: Long, val payload: ByteArray)

    private val frames = HashMap<Int, Frame>()
    private var started = false
    private var next = 0

    fun pending(): Int = frames.size

    fun enqueue(seq: Int, rtp: Long, payload: ByteArray): String? {
        val key = seq and 0xFFFF
        if (started && MirrorAudio.seqCmp(key, next) < 0) return "late"
        if (frames.containsKey(key)) return "dup"
        frames[key] = Frame(key, rtp, payload)
        while (frames.size > limit) {
            val oldest = oldestSeq() ?: break
            frames.remove(oldest)
            if (started && oldest == next) next = (next + 1) and 0xFFFF
        }
        return null
    }

    fun dequeue(force: Boolean): Frame? {
        if (frames.isEmpty()) return null
        if (!started) {
            if (!force && frames.size < 4) return null
            next = oldestSeq() ?: return null
            started = true
        }
        val frame = frames.remove(next)
        if (frame == null) {
            if (!force) return null
            next = (next + 1) and 0xFFFF
            return null
        }
        next = (next + 1) and 0xFFFF
        return frame
    }

    fun clear() {
        frames.clear()
        started = false
        next = 0
    }

    private fun oldestSeq(): Int? {
        var best: Int? = null
        for (seq in frames.keys) {
            if (best == null || MirrorAudio.seqCmp(seq, best) < 0) best = seq
        }
        return best
    }
}
