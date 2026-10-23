package com.wddclass.clicktocast.dlna

/** DLNA time values: `H+:MM:SS[.F+]`, plus a leading `+` or `-` for relative seeks. */
object DlnaTime {
    fun format(ms: Long): String {
        if (ms < 0) return "00:00:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    fun formatClock(ms: Long): String = if (ms < 0) "--:--:--" else format(ms)

    fun parse(raw: String, currentMs: Long = 0L): Long? {
        val text = raw.trim()
        if (text.isEmpty() || text.equals("NOT_IMPLEMENTED", ignoreCase = true)) return null
        val sign = when {
            text.startsWith("+") -> 1
            text.startsWith("-") -> -1
            else -> 0
        }
        val body = if (sign == 0) text else text.substring(1).trim()
        val absolute = parseAbsolute(body) ?: return null
        val resolved = if (sign == 0) absolute else currentMs + sign * absolute
        return resolved.coerceAtLeast(0L)
    }

    private fun parseAbsolute(body: String): Long? {
        if (!body.contains(':')) {
            val seconds = body.toDoubleOrNull() ?: return null
            if (seconds < 0) return null
            return (seconds * 1000.0).toLong()
        }
        val parts = body.split(':')
        if (parts.size != 2 && parts.size != 3) return null
        val hoursText = if (parts.size == 3) parts[0] else "0"
        val minutesText = if (parts.size == 3) parts[1] else parts[0]
        val secondsText = parts.last()
        val hours = hoursText.toLongOrNull() ?: return null
        val minutes = minutesText.toLongOrNull() ?: return null
        val seconds = secondsText.toDoubleOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || seconds < 0.0 || seconds >= 60.0) return null
        return hours * 3_600_000L + minutes * 60_000L + (seconds * 1000.0).toLong()
    }
}
