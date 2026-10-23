package com.wddclass.clicktocast.dlna

/** Playback rates the on-screen menu can apply. Phone Play still accepts only 1. */
object PlaybackRates {
    val steps: List<Double> = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

    fun label(rate: Double): String? {
        val match = steps.firstOrNull { kotlin.math.abs(it - rate) < 0.001 } ?: return null
        return if (match == match.toLong().toDouble()) match.toLong().toString() else match.toString()
    }

    fun matchesPhonePlay(raw: String): Boolean {
        val text = raw.trim()
        return text.isEmpty() || text == "1" || text == "1.0"
    }
}
