package com.wddclass.clicktocast.airplay

/**
 * AirPlay timing packets are 32 bytes. The receiver sends `80 d2` to the
 * client port named in SETUP, and answers an inbound `80 d2` with `80 d3`.
 * Timestamps are NTP (seconds since 1900, big endian) plus a 32-bit fraction.
 */
internal object MirrorTiming {
    private const val NTP_EPOCH_DELTA = 2_208_988_800L

    fun request(unixNanos: Long): ByteArray {
        val packet = ByteArray(32)
        packet[0] = 0x80.toByte()
        packet[1] = 0xd2.toByte()
        packet[3] = 0x07
        putNtp(packet, 24, unixNanos)
        return packet
    }

    fun reply(request: ByteArray, receiveNanos: Long, sendNanos: Long): ByteArray {
        val packet = ByteArray(32)
        packet[0] = 0x80.toByte()
        packet[1] = 0xd3.toByte()
        packet[3] = 0x07
        if (request.size >= 32) {
            request.copyInto(packet, destinationOffset = 8, startIndex = 24, endIndex = 32)
        }
        putNtp(packet, 16, receiveNanos)
        putNtp(packet, 24, sendNanos)
        return packet
    }

    fun putNtp(packet: ByteArray, offset: Int, unixNanos: Long) {
        val seconds = unixNanos / 1_000_000_000L + NTP_EPOCH_DELTA
        val nanos = unixNanos % 1_000_000_000L
        val fraction = (nanos shl 32) / 1_000_000_000L
        putInt(packet, offset, seconds.toInt())
        putInt(packet, offset + 4, fraction.toInt())
    }

    private fun putInt(packet: ByteArray, offset: Int, value: Int) {
        packet[offset] = (value ushr 24).toByte()
        packet[offset + 1] = (value ushr 16).toByte()
        packet[offset + 2] = (value ushr 8).toByte()
        packet[offset + 3] = value.toByte()
    }
}
