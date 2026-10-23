package com.wddclass.clicktocast.airplay

/** 128-byte AirPlay mirror header plus H.264 payload helpers. */
internal object MirrorPackets {
    data class Header(
        val payloadSize: Int,
        val type: Int,
        val option: Int,
        val ntpRaw: Long,
        val width: Int,
        val height: Int,
    )

    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun header(packet: ByteArray): Header? {
        if (packet.size < 128) return null
        val width = leFloat(packet, 40).toInt().takeIf { it in 16..7680 }
            ?: leFloat(packet, 56).toInt().takeIf { it in 16..7680 }
            ?: 0
        val height = leFloat(packet, 44).toInt().takeIf { it in 16..4320 }
            ?: leFloat(packet, 60).toInt().takeIf { it in 16..4320 }
            ?: 0
        return Header(
            payloadSize = leInt(packet, 0),
            type = packet[4].toInt() and 0xFF,
            option = packet[5].toInt() and 0xFF,
            ntpRaw = leLong(packet, 8),
            width = width,
            height = height,
        )
    }

    /** Length-prefixed NALs become Annex-B. Returns null when the lengths do not fit. */
    fun toAnnexB(payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return null
        val out = payload.copyOf()
        var offset = 0
        var count = 0
        while (offset < out.size) {
            if (offset + 4 > out.size) return null
            val length = beInt(out, offset)
            if (length <= 0 || offset + 4 + length > out.size) return null
            if ((out[offset + 4].toInt() and 0x80) != 0) return null
            out[offset] = 0
            out[offset + 1] = 0
            out[offset + 2] = 0
            out[offset + 3] = 1
            offset += 4 + length
            count++
            if (count > 64) return null
        }
        return if (offset == out.size) out else null
    }

    /**
     * Unencrypted type-1 H.264 packet: 6-byte prefix, big-endian SPS size at 6,
     * SPS at 8, then a big-endian PPS size and the PPS.
     */
    fun extractH264(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        if (payload.size < 11) return null
        val spsSize = beShort(payload, 6)
        if (spsSize <= 0 || 8 + spsSize + 2 > payload.size) return null
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsSize = beShort(payload, spsSize + 9)
        val ppsAt = spsSize + 11
        if (ppsSize <= 0 || ppsAt + ppsSize > payload.size) return null
        return sps to payload.copyOfRange(ppsAt, ppsAt + ppsSize)
    }

    fun annex(nal: ByteArray): ByteArray = startCode + nal

    fun leInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    fun beInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun beShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun leLong(bytes: ByteArray, offset: Int): Long {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((bytes[offset + index].toLong() and 0xFF) shl (8 * index))
        }
        return value
    }

    private fun leFloat(bytes: ByteArray, offset: Int): Float = Float.fromBits(leInt(bytes, offset))
}
