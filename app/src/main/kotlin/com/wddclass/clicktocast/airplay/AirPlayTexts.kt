package com.wddclass.clicktocast.airplay

import java.net.URLDecoder
import java.util.Locale

data class AirPlayIdentity(val name: String, val deviceId: String)

data class PlayCommand(val url: String, val startFraction: Double)

data class AirPlayStatus(
    val positionSec: Double,
    val durationSec: Double,
    val rate: Double,
    val ready: Boolean,
)

/**
 * AirPlay 1 video URL playback: mDNS text records and the HTTP /play body.
 * Screen mirroring and FairPlay are not implemented.
 */
object AirPlayTexts {
    const val SRCVERS = "220.68"
    const val MODEL = "AppleTV5,3"
    const val FEATURES = "0x5A7FFFF7,0x1E"

    /**
     * Ed25519 public key from the public RFC 8032 test vector.
     * The picker uses it as the TXT `pk` value. Pairing is not implemented,
     * so this key is not a secret and cannot decrypt a mirrored stream.
     */
    const val PUBLIC_KEY = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="

    fun macFromUuid(uuid: String): String {
        val hex = uuid.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        val padded = (hex + "000000000000").take(12)
        val first = (padded.substring(0, 2).toInt(16) or 0x02) and 0xFE
        val bytes = listOf("%02X".format(first)) + padded.drop(2).chunked(2).map { it.uppercase() }
        return bytes.joinToString(":")
    }

    fun pi(deviceId: String): String {
        val hex = deviceId.replace(":", "").lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
        val padded = hex.padEnd(32, '0').take(32)
        return padded.substring(0, 8) + "-" +
            padded.substring(8, 12) + "-" +
            padded.substring(12, 16) + "-" +
            padded.substring(16, 20) + "-" +
            padded.substring(20, 32)
    }

    fun airplayTxt(deviceId: String): Map<String, String> = linkedMapOf(
        "deviceid" to deviceId,
        "features" to FEATURES,
        "model" to MODEL,
        "srcvers" to SRCVERS,
        "vv" to "2",
        "flags" to "0x4",
        "pw" to "false",
        "pk" to PUBLIC_KEY,
        "pi" to pi(deviceId),
    )

    fun raopTxt(deviceId: String): Map<String, String> = linkedMapOf(
        "txtvers" to "1",
        "ch" to "2",
        "cn" to "0,1",
        "et" to "0,4",
        "sv" to "false",
        "da" to "true",
        "sr" to "44100",
        "ss" to "16",
        "pw" to "false",
        "vn" to "3",
        "tp" to "UDP",
        "md" to "0,1,2",
        "vs" to SRCVERS,
        "am" to MODEL,
        "sf" to "0x4",
        "ft" to FEATURES,
        "pk" to PUBLIC_KEY,
        "deviceid" to deviceId,
    )

    fun raopInstanceName(deviceId: String, friendlyName: String): String {
        val mac = deviceId.replace(":", "").uppercase()
        val name = friendlyName.replace("@", "").trim().ifBlank { "投屏点我" }
        val combined = "$mac@$name"
        val bytes = combined.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 63) return combined
        var kept = name
        while (kept.isNotEmpty() && "$mac@$kept".toByteArray(Charsets.UTF_8).size > 63) {
            kept = kept.dropLast(1)
        }
        return "$mac@$kept"
    }

    fun serverInfo(deviceId: String): String = plist(
        """
        <key>deviceid</key><string>${escape(deviceId)}</string>
        <key>features</key><integer>1515847927</integer>
        <key>model</key><string>$MODEL</string>
        <key>protovers</key><string>1.0</string>
        <key>srcvers</key><string>$SRCVERS</string>
        <key>vv</key><integer>2</integer>
        """.trimIndent(),
    )

    fun playbackInfo(status: AirPlayStatus): String = plist(
        """
        <key>duration</key><real>${num(status.durationSec)}</real>
        <key>position</key><real>${num(status.positionSec)}</real>
        <key>rate</key><real>${num(status.rate)}</real>
        <key>readyToPlay</key><${if (status.ready) "true" else "false"}/>
        <key>playbackBufferEmpty</key><false/>
        <key>playbackBufferFull</key><false/>
        <key>playbackLikelyToKeepUp</key><${if (status.ready) "true" else "false"}/>
        """.trimIndent(),
    )

    fun scrubText(status: AirPlayStatus): String =
        "duration: ${num(status.durationSec)}\nposition: ${num(status.positionSec)}\n"

    fun playCommand(contentLocation: String?, body: ByteArray): PlayCommand? {
        val values = bodyValues(body).toMutableMap()
        val headerUrl = contentLocation?.trim().orEmpty()
        if (headerUrl.isNotEmpty() && "Content-Location" !in values) values["Content-Location"] = headerUrl
        val url = values["Content-Location"]?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val start = values["Start-Position"]?.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.0
        return PlayCommand(url, start)
    }

    fun queryValue(query: String, name: String): String? {
        for (part in query.split('&')) {
            if (part.isBlank()) continue
            val key = part.substringBefore('=')
            if (key != name) continue
            val raw = part.substringAfter('=', "")
            return try {
                URLDecoder.decode(raw, "UTF-8")
            } catch (_: Exception) {
                raw
            }
        }
        return null
    }

    private fun bodyValues(body: ByteArray): Map<String, String> {
        if (body.isEmpty()) return emptyMap()
        if (body.size >= 8 && body.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            return binaryDictionary(body)
        }
        val text = String(body, Charsets.UTF_8).trimStart('\uFEFF')
        if (text.contains("<plist") || text.contains("<key>")) return xmlValues(text)
        return textValues(text)
    }

    private fun textValues(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (line in text.split('\n')) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            values[line.substring(0, index).trim()] = line.substring(index + 1).trim()
        }
        return values
    }

    private fun xmlValues(text: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var cursor = 0
        while (cursor < text.length) {
            val keyAt = text.indexOf("<key>", cursor)
            if (keyAt < 0) break
            val keyEnd = text.indexOf("</key>", keyAt)
            if (keyEnd < 0) break
            val key = text.substring(keyAt + 5, keyEnd).trim()
            val rest = text.substring(keyEnd + 6).trimStart()
            val value = when {
                rest.startsWith("<string>") -> inside(rest, "<string>", "</string>")
                rest.startsWith("<real>") -> inside(rest, "<real>", "</real>")
                rest.startsWith("<integer>") -> inside(rest, "<integer>", "</integer>")
                rest.startsWith("<true") -> "true"
                rest.startsWith("<false") -> "false"
                else -> null
            }
            if (value != null) values[key] = value
            cursor = keyEnd + 6
        }
        return values
    }

    private fun inside(text: String, open: String, close: String): String? {
        val start = text.indexOf(open)
        val end = text.indexOf(close, start + open.length)
        if (start < 0 || end < 0) return null
        return text.substring(start + open.length, end).trim()
    }

    private fun binaryDictionary(bytes: ByteArray): Map<String, String> {
        val parsed = try {
            BinaryPlist.top(bytes)
        } catch (_: Exception) {
            null
        }
        val dict = parsed as? Map<*, *> ?: return emptyMap()
        val values = linkedMapOf<String, String>()
        for ((key, value) in dict) {
            if (key !is String || value == null) continue
            values[key] = when (value) {
                is String -> value
                is Double -> value.toString()
                is Long -> value.toString()
                is Boolean -> value.toString()
                else -> value.toString()
            }
        }
        return values
    }

    private fun plist(body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
$body
</dict></plist>
"""

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun num(value: Double): String = String.format(Locale.US, "%.3f", value)
}

/** Enough of Apple's binary plist to read the dictionary POST /play sends. */
internal object BinaryPlist {
    fun top(bytes: ByteArray): Any? = parse(bytes)

    private fun parse(bytes: ByteArray): Any? {
        if (bytes.size < 40) return null
        val trailer = bytes.size - 32
        val offsetSize = u8(bytes, trailer + 6)
        val refSize = u8(bytes, trailer + 7)
        if (offsetSize !in 1..4 || refSize !in 1..4) return null
        val count = readInt(bytes, trailer + 8, 8)
        val root = readInt(bytes, trailer + 16, 8)
        val table = readInt(bytes, trailer + 24, 8)
        if (count <= 0 || root !in 0 until count || table < 0) return null
        fun offsetOf(index: Int): Int = readInt(bytes, table + index * offsetSize, offsetSize)
        val cache = arrayOfNulls<Any>(count)
        val seen = BooleanArray(count)
        fun readRef(index: Int): Any? {
            if (index !in 0 until count) return null
            if (seen[index]) return cache[index]
            seen[index] = true
            val value = decode(bytes, offsetOf(index), refSize, ::readRef)
            cache[index] = value
            return value
        }
        return readRef(root)
    }

    private fun decode(bytes: ByteArray, offset: Int, refSize: Int, ref: (Int) -> Any?): Any? {
        if (offset !in bytes.indices) return null
        val marker = u8(bytes, offset)
        val type = marker ushr 4
        if (type == 0) {
            return when (marker) {
                0x08 -> false
                0x09 -> true
                else -> null
            }
        }
        var size = marker and 0x0F
        var cursor = offset + 1
        if (size == 0x0F) {
            if (cursor >= bytes.size) return null
            val lengthMarker = u8(bytes, cursor)
            val lengthBytes = 1 shl (lengthMarker and 0x0F)
            cursor += 1
            size = readInt(bytes, cursor, lengthBytes)
            cursor += lengthBytes
        }
        if (type == 0x1 || type == 0x2) {
            val width = 1 shl size
            if (type == 0x1) return readLong(bytes, cursor, width)
            if (width == 4 || width == 8) {
                val bits = readLong(bytes, cursor, width)
                val wide = if (width == 4) java.lang.Float.intBitsToFloat(bits.toInt()).toDouble() else java.lang.Double.longBitsToDouble(bits)
                return wide
            }
            return null
        }
        if (type == 0x5) {
            return String(bytes, cursor, size, Charsets.US_ASCII)
        }
        if (type == 0x6) {
            return String(bytes, cursor, size * 2, Charsets.UTF_16BE)
        }
        if (type == 0x4) {
            return bytes.copyOfRange(cursor, cursor + size)
        }
        if (type == 0xA) {
            return List(size) { index -> ref(readInt(bytes, cursor + index * refSize, refSize)) }
        }
        if (type == 0xD) {
            val dict = linkedMapOf<String, Any?>()
            for (index in 0 until size) {
                val key = ref(readInt(bytes, cursor + index * refSize, refSize)) as? String ?: continue
                val value = ref(readInt(bytes, cursor + (size + index) * refSize, refSize))
                dict[key] = value
            }
            return dict
        }
        return null
    }

    private fun u8(bytes: ByteArray, offset: Int): Int = bytes[offset].toInt() and 0xFF

    private fun readInt(bytes: ByteArray, offset: Int, size: Int): Int = readLong(bytes, offset, size).toInt()

    private fun readLong(bytes: ByteArray, offset: Int, size: Int): Long {
        var value = 0L
        val width = size.coerceIn(0, 8)
        for (index in 0 until width) {
            value = (value shl 8) or (bytes[offset + index].toLong() and 0xFF)
        }
        return value
    }
}
