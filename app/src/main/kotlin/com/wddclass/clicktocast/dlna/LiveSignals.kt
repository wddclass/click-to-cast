package com.wddclass.clicktocast.dlna

/**
 * Live when the player says so, the HLS playlist has no ENDLIST, the item is
 * ready with no positive duration, or DLNA metadata duration is 0 / NOT_IMPLEMENTED
 * and the player has not since learned a length.
 */
object LiveSignals {
    fun metadataDeclaresLive(metadata: String): Boolean {
        if (metadata.isBlank()) return false
        var declared = false
        for (value in attributeValues(metadata, "duration") + tagValues(metadata, "duration")) {
            if (unset(value)) declared = true
        }
        return declared
    }

    fun fromEngine(
        playerLive: Boolean,
        hlsManifestLoaded: Boolean,
        hlsWithoutEndList: Boolean,
        durationMs: Long,
        ready: Boolean,
    ): Boolean {
        if (playerLive || hlsWithoutEndList) return true
        if (hlsManifestLoaded) return false
        return ready && durationMs <= 0L
    }

    fun combine(engineLive: Boolean, metadataLive: Boolean, durationMs: Long): Boolean {
        if (engineLive) return true
        return metadataLive && durationMs <= 0L
    }

    private fun unset(value: String): Boolean {
        val text = value.trim()
        if (text.equals("NOT_IMPLEMENTED", ignoreCase = true)) return true
        if (text == "0" || text == "0.0" || text == "0.00" || text == "0.000") return true
        val parsed = DlnaTime.parse(text) ?: return false
        return parsed == 0L
    }

    private fun attributeValues(metadata: String, name: String): List<String> {
        val pattern = Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
        return pattern.findAll(metadata).map { it.groupValues[1] }.toList()
    }

    private fun tagValues(metadata: String, name: String): List<String> {
        val pattern = Regex("""<[^>]*\b$name\b[^>]*>([^<]*)</""", RegexOption.IGNORE_CASE)
        return pattern.findAll(metadata).map { it.groupValues[1] }.toList()
    }
}
