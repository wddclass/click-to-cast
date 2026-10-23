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
        val displayWidth: Int,
        val displayHeight: Int,
    )

    private val startCode = byteArrayOf(0, 0, 0, 1)

    fun header(packet: ByteArray): Header? {
        if (packet.size < 128) return null
        val sourceWidth = leFloat(packet, 40).toInt()
        val sourceHeight = leFloat(packet, 44).toInt()
        val displayWidth = leFloat(packet, 56).toInt()
        val displayHeight = leFloat(packet, 60).toInt()
        val width = sourceWidth.takeIf { it in 16..7680 }
            ?: displayWidth.takeIf { it in 16..7680 }
            ?: 0
        val height = sourceHeight.takeIf { it in 16..4320 }
            ?: displayHeight.takeIf { it in 16..4320 }
            ?: 0
        return Header(
            payloadSize = leInt(packet, 0),
            type = packet[4].toInt() and 0xFF,
            option = packet[5].toInt() and 0xFF,
            ntpRaw = leLong(packet, 8),
            width = width,
            height = height,
            displayWidth = displayWidth.takeIf { it in 16..7680 } ?: 0,
            displayHeight = displayHeight.takeIf { it in 16..4320 } ?: 0,
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
            if (count > 256) return null
        }
        return if (offset == out.size) out else null
    }

    /** Why [toAnnexB] rejected a decrypted payload, for the log. */
    fun annexProblem(payload: ByteArray): String {
        if (payload.isEmpty()) return "empty"
        var offset = 0
        var count = 0
        while (offset < payload.size) {
            if (offset + 4 > payload.size) return "short length at $offset"
            val length = beInt(payload, offset)
            if (length <= 0) return "length=$length at $offset"
            if (offset + 4 + length > payload.size) {
                return "length=$length at $offset exceeds ${payload.size}"
            }
            val nal = payload[offset + 4].toInt() and 0xFF
            if ((nal and 0x80) != 0) return "forbidden bit nal=0x${nal.toString(16)} at $offset"
            offset += 4 + length
            count++
            if (count > 256) return "too many nals"
        }
        return if (offset == payload.size) "ok" else "tail"
    }

    /** H.264 nal_unit_type values inside an Annex-B buffer, in order. */
    fun nalTypes(annex: ByteArray): String {
        val types = ArrayList<Int>()
        var offset = 0
        while (offset + 5 <= annex.size && types.size < 8) {
            if (annex[offset].toInt() == 0 && annex[offset + 1].toInt() == 0 &&
                annex[offset + 2].toInt() == 0 && annex[offset + 3].toInt() == 1
            ) {
                types += annex[offset + 4].toInt() and 0x1F
                offset += 4
            } else {
                offset++
            }
        }
        return types.joinToString(",")
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

/** Cropped display size from an H.264 SPS NAL, without the start code. */
internal object H264Sps {
    private val highProfiles = intArrayOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)

    fun displaySize(sps: ByteArray): Pair<Int, Int>? {
        if (sps.size < 4) return null
        return try {
            val reader = BitReader(unescape(sps.copyOfRange(1, sps.size)))
            val profile = reader.u(8)
            reader.u(8)
            reader.u(8)
            reader.ue()
            var chroma = 1
            if (profile in highProfiles) {
                chroma = reader.ue()
                if (chroma == 3) reader.u(1)
                reader.ue()
                reader.ue()
                reader.u(1)
                if (reader.u(1) == 1) {
                    val lists = if (chroma != 3) 8 else 12
                    for (index in 0 until lists) {
                        if (reader.u(1) == 1) skipScalingList(reader, if (index < 6) 16 else 64)
                    }
                }
            }
            reader.ue()
            val poc = reader.ue()
            if (poc == 0) {
                reader.ue()
            } else if (poc == 1) {
                reader.u(1)
                reader.se()
                reader.se()
                val offsets = reader.ue()
                repeat(offsets) { reader.se() }
            }
            reader.ue()
            reader.u(1)
            val widthMbs = reader.ue() + 1
            val heightUnits = reader.ue() + 1
            val frameOnly = reader.u(1)
            if (frameOnly == 0) reader.u(1)
            reader.u(1)
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.u(1) == 1) {
                cropLeft = reader.ue()
                cropRight = reader.ue()
                cropTop = reader.ue()
                cropBottom = reader.ue()
            }
            val cropX = if (chroma == 0 || chroma == 3) 1 else 2
            val cropY = (if (chroma == 1) 2 else 1) * (2 - frameOnly)
            val width = widthMbs * 16 - (cropLeft + cropRight) * cropX
            val height = heightUnits * 16 * (2 - frameOnly) - (cropTop + cropBottom) * cropY
            if (width !in 16..7680 || height !in 16..4320) null else width to height
        } catch (_: Throwable) {
            null
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var last = 8
        var next = 8
        for (index in 0 until size) {
            if (next != 0) {
                next = (last + reader.se() + 256) % 256
            }
            if (next != 0) last = next
        }
    }

    private fun unescape(src: ByteArray): ByteArray {
        val out = ArrayList<Byte>(src.size)
        var index = 0
        while (index < src.size) {
            if (index + 2 < src.size && src[index].toInt() == 0 && src[index + 1].toInt() == 0 &&
                src[index + 2].toInt() == 3
            ) {
                out.add(0)
                out.add(0)
                index += 3
            } else {
                out.add(src[index])
                index++
            }
        }
        return out.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var bit = 0

        fun u(count: Int): Int {
            var value = 0
            repeat(count) {
                if (bit >= data.size * 8) throw IllegalArgumentException("short sps")
                val current = data[bit ushr 3].toInt() and 0xFF
                value = (value shl 1) or ((current ushr (7 - (bit and 7))) and 1)
                bit++
            }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (u(1) == 0) {
                zeros++
                if (zeros > 31) throw IllegalArgumentException("ue")
            }
            if (zeros == 0) return 0
            return ((1 shl zeros) - 1) + u(zeros)
        }

        fun se(): Int {
            val code = ue()
            return if (code and 1 == 1) (code + 1) / 2 else -(code / 2)
        }
    }
}
