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

    /** Apple TV 3,2 is the unauthenticated video-URL generation. 5,3 asks for FairPlay. */
    const val MODEL = "AppleTV3,2"

    /** Low 32 bits, then high 32 bits, as mDNS TXT writes them. */
    const val FEATURES = "0x5A7FFFF7,0x1E"

    /** Same bits as one integer: (0x1E << 32) | 0x5A7FFFF7. /info must use this, not the low half. */
    const val FEATURES_INT = 130367356919L

    /**
     * 32-byte Ed25519 public key from the public RFC 8032 test vector, hex for the TXT `pk`.
     * The matching plist value is these bytes, not the hex text. Pairing is not implemented,
     * so this key cannot decrypt a mirrored stream.
     */
    const val PUBLIC_KEY = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"

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

    fun publicKeyBytes(): ByteArray {
        val hex = PUBLIC_KEY
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            out[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    /** DNS-SD TXT wire format: a length byte plus `key=value` for each entry. */
    fun packTxt(txt: Map<String, String>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in txt) {
            if (key.isEmpty()) continue
            var text = "$key=$value".toByteArray(Charsets.UTF_8)
            if (text.size > 255) text = text.copyOf(255)
            if (text.isEmpty()) continue
            out.write(text.size)
            out.write(text)
        }
        if (out.size() == 0) out.write(0)
        return out.toByteArray()
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

    /**
     * Binary plist for RTSP `GET /info`. Keys and the public key bytes match the mDNS TXT.
     * statusFlags 4 is the usual "ready, no password" value. Screen mirroring is not offered
     * as a display stream; FairPlay is not implemented.
     */
    fun infoPlist(name: String, deviceId: String): ByteArray {
        val shown = name.trim().ifBlank { "投屏点我" }
        val identifier = pi(deviceId)
        val airplay = airplayTxt(deviceId)
        val raop = raopTxt(deviceId)
        val dict = linkedMapOf<String, Any>(
            "deviceID" to deviceId,
            "macAddress" to deviceId,
            "features" to FEATURES_INT,
            "model" to MODEL,
            "protovers" to "1.0",
            "protocolVersion" to "1.0",
            "srcvers" to SRCVERS,
            "sourceVersion" to SRCVERS,
            "vv" to 2,
            "statusFlags" to 4,
            "name" to shown,
            "pk" to publicKeyBytes(),
            "pi" to identifier,
            "txtAirPlay" to packTxt(airplay),
            "txtRAOP" to packTxt(raop),
            "keepAliveLowPower" to false,
            "keepAliveSendStatsAsBody" to false,
            "audioFormats" to listOf(
                linkedMapOf<String, Any>(
                    "type" to 96,
                    "audioInputFormats" to 0x01000000,
                    "audioOutputFormats" to 0x01000000,
                ),
            ),
            "audioLatencies" to listOf(
                linkedMapOf<String, Any>(
                    "type" to 96,
                    "audioType" to "default",
                    "inputLatencyMicros" to 0,
                    "outputLatencyMicros" to 0,
                ),
            ),
        )
        return BinaryPlist.write(dict)
    }

    fun serverInfo(deviceId: String): String = plist(
        """
        <key>deviceid</key><string>${escape(deviceId)}</string>
        <key>deviceID</key><string>${escape(deviceId)}</string>
        <key>features</key><integer>$FEATURES_INT</integer>
        <key>model</key><string>$MODEL</string>
        <key>protovers</key><string>1.0</string>
        <key>srcvers</key><string>$SRCVERS</string>
        <key>sourceVersion</key><string>$SRCVERS</string>
        <key>vv</key><integer>2</integer>
        <key>statusFlags</key><integer>4</integer>
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

/** Enough of Apple's binary plist to read POST /play and to write GET /info. */
internal object BinaryPlist {
    fun top(bytes: ByteArray): Any? = parse(bytes)

    fun write(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()
        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Node.Pending)
            nodes[index] = when (value) {
                is Boolean -> Node.Bool(value)
                is Int -> Node.IntNum(value.toLong())
                is Long -> Node.IntNum(value)
                is Double -> Node.Real(value)
                is Float -> Node.Real(value.toDouble())
                is ByteArray -> Node.Data(value)
                is String -> if (value.all { it.code in 0..127 }) Node.Ascii(value) else Node.Utf16(value)
                is List<*> -> Node.Array(value.map { add(it) }.toIntArray())
                is Map<*, *> -> {
                    val keys = value.keys.map { add(it?.toString() ?: "") }.toIntArray()
                    val vals = value.values.map { add(it) }.toIntArray()
                    Node.Dict(keys, vals)
                }
                else -> Node.Ascii(value?.toString() ?: "")
            }
            return index
        }
        add(root)
        val refSize = when {
            nodes.size <= 0xFF -> 1
            nodes.size <= 0xFFFF -> 2
            else -> 4
        }
        val objects = nodes.map { encode(it, refSize) }
        var cursor = 8
        val offsets = IntArray(objects.size)
        for (index in objects.indices) {
            offsets[index] = cursor
            cursor += objects[index].size
        }
        val offsetSize = when {
            cursor <= 0xFF -> 1
            cursor <= 0xFFFF -> 2
            else -> 4
        }
        val out = java.io.ByteArrayOutputStream()
        out.write("bplist00".toByteArray(Charsets.US_ASCII))
        for (objectBytes in objects) out.write(objectBytes)
        val tableAt = cursor
        for (offset in offsets) writeBe(out, offset.toLong(), offsetSize)
        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        putBe(trailer, 8, nodes.size.toLong(), 8)
        putBe(trailer, 24, tableAt.toLong(), 8)
        out.write(trailer)
        return out.toByteArray()
    }

    private fun encode(node: Node, refSize: Int): ByteArray = when (node) {
        Node.Pending -> byteArrayOf(0)
        is Node.Bool -> byteArrayOf(if (node.value) 0x09 else 0x08)
        is Node.IntNum -> intObject(node.value)
        is Node.Real -> {
            val bits = java.lang.Double.doubleToRawLongBits(node.value)
            val out = ByteArray(9)
            out[0] = 0x23
            putBe(out, 1, bits, 8)
            out
        }
        is Node.Data -> bytesObject(0x40, node.value)
        is Node.Ascii -> bytesObject(0x50, node.value.toByteArray(Charsets.US_ASCII))
        is Node.Utf16 -> bytesObject(0x60, node.value.toByteArray(Charsets.UTF_16BE), node.value.length)
        is Node.Array -> refsObject(0xA0, node.refs, refSize)
        is Node.Dict -> {
            val refs = IntArray(node.keys.size + node.values.size)
            node.keys.copyInto(refs, 0)
            node.values.copyInto(refs, node.keys.size)
            refsObject(0xD0, refs, refSize, node.keys.size)
        }
    }

    private fun intObject(value: Long): ByteArray {
        val width = when {
            value in 0..0xFF -> 1
            value in 0..0xFFFF -> 2
            value in 0..0xFFFF_FFFFL -> 4
            else -> 8
        }
        val marker = when (width) {
            1 -> 0x10
            2 -> 0x11
            4 -> 0x12
            else -> 0x13
        }
        val out = ByteArray(1 + width)
        out[0] = marker.toByte()
        putBe(out, 1, value, width)
        return out
    }

    private fun bytesObject(markerBase: Int, payload: ByteArray, count: Int = payload.size): ByteArray {
        val head = countHead(markerBase, count)
        val out = ByteArray(head.size + payload.size)
        head.copyInto(out, 0)
        payload.copyInto(out, head.size)
        return out
    }

    private fun refsObject(markerBase: Int, refs: IntArray, refSize: Int, count: Int = refs.size): ByteArray {
        val head = countHead(markerBase, count)
        val out = ByteArray(head.size + refs.size * refSize)
        head.copyInto(out, 0)
        var cursor = head.size
        for (ref in refs) {
            putBe(out, cursor, ref.toLong(), refSize)
            cursor += refSize
        }
        return out
    }

    private fun countHead(markerBase: Int, count: Int): ByteArray {
        if (count < 15) return byteArrayOf((markerBase or count).toByte())
        val length = intObject(count.toLong())
        val out = ByteArray(1 + length.size)
        out[0] = (markerBase or 0x0F).toByte()
        length.copyInto(out, 1)
        return out
    }

    private fun writeBe(out: java.io.ByteArrayOutputStream, value: Long, width: Int) {
        val bytes = ByteArray(width)
        putBe(bytes, 0, value, width)
        out.write(bytes)
    }

    private fun putBe(target: ByteArray, offset: Int, value: Long, width: Int) {
        for (index in 0 until width) {
            val shift = (width - 1 - index) * 8
            target[offset + index] = ((value ushr shift) and 0xFF).toByte()
        }
    }

    private sealed class Node {
        data object Pending : Node()
        data class Bool(val value: Boolean) : Node()
        data class IntNum(val value: Long) : Node()
        data class Real(val value: Double) : Node()
        data class Data(val value: ByteArray) : Node()
        data class Ascii(val value: String) : Node()
        data class Utf16(val value: String) : Node()
        data class Array(val refs: IntArray) : Node()
        data class Dict(val keys: IntArray, val values: IntArray) : Node()
    }

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
