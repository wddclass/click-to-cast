package com.wddclass.clicktocast.dlna

import java.util.Locale

/** Formats a one-second byte count as a download speed, for example `2.3 MB/s`. */
object NetSpeed {
    fun format(bytesPerSecond: Long): String {
        val bytes = bytesPerSecond.coerceAtLeast(0L)
        if (bytes >= 1_000_000L) {
            return String.format(Locale.US, "%.1f MB/s", bytes / 1_000_000.0)
        }
        return String.format(Locale.US, "%.0f KB/s", bytes / 1_000.0)
    }
}
