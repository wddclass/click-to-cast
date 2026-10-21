package com.wddclass.clicktocast.dlna

enum class MediaKind {
    HLS,
    DASH,
    FLV,
    PROGRESSIVE,
    UNKNOWN,
}

/**
 * Classifies a cast URL from the address, the HTTP Content-Type, and a short byte sniff.
 * Byte sniff wins so an HLS playlist mislabeled as `.mp4` is not handed to the MP4 extractor.
 */
object MediaKindDetector {
    fun detect(url: String, contentType: String?, head: ByteArray?): MediaKind {
        classifyBytes(head)?.let { return it }
        val type = contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            type.contains("mpegurl") || type.contains("vnd.apple.mpegurl") -> return MediaKind.HLS
            type.contains("dash+xml") -> return MediaKind.DASH
            type.contains("x-flv") || type == "video/flv" -> return MediaKind.FLV
            type.startsWith("video/") || type.startsWith("audio/") -> return MediaKind.PROGRESSIVE
        }
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") -> MediaKind.HLS
            path.endsWith(".mpd") -> MediaKind.DASH
            path.endsWith(".flv") -> MediaKind.FLV
            path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") ||
                path.endsWith(".mkv") || path.endsWith(".webm") || path.endsWith(".ts") ->
                MediaKind.PROGRESSIVE
            else -> MediaKind.UNKNOWN
        }
    }

    private fun classifyBytes(head: ByteArray?): MediaKind? {
        if (head == null || head.isEmpty()) return null
        val bytes = stripBom(head)
        if (bytes.size >= 3 &&
            bytes[0] == 'F'.code.toByte() &&
            bytes[1] == 'L'.code.toByte() &&
            bytes[2] == 'V'.code.toByte()
        ) {
            return MediaKind.FLV
        }
        if (bytes.size >= 12) {
            val brand = String(bytes.copyOfRange(4, 8), Charsets.US_ASCII)
            if (brand == "ftyp") return MediaKind.PROGRESSIVE
        }
        val text = String(bytes, Charsets.ISO_8859_1).trimStart()
        if (text.startsWith("#EXTM3U")) return MediaKind.HLS
        if (text.contains("<MPD")) return MediaKind.DASH
        return null
    }

    private fun stripBom(bytes: ByteArray): ByteArray {
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() &&
            bytes[1] == 0xBB.toByte() &&
            bytes[2] == 0xBF.toByte()
        ) {
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }
}
