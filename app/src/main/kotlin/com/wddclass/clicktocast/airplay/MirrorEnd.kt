package com.wddclass.clicktocast.airplay

/**
 * When to leave a mirror that never sent TEARDOWN.
 * Video or `/feedback` every couple of seconds keeps it alive.
 * Timing silence only counts once those have also stopped.
 */
internal object MirrorEnd {
    const val GRACE_MS = 6_000L
    const val IDLE_MS = 4_500L
    const val TIMING_MS = 8_000L

    fun reason(
        now: Long,
        openedAt: Long,
        lastVideo: Long,
        lastFeedback: Long,
        lastTiming: Long,
    ): String? {
        if (openedAt <= 0L || now - openedAt < GRACE_MS) return null
        val videoAge = age(now, lastVideo, openedAt)
        val feedbackAge = age(now, lastFeedback, openedAt)
        val seen = lastVideo > 0L || lastFeedback > 0L
        if (!seen) return null
        val quiet = minOf(videoAge, feedbackAge)
        if (quiet < IDLE_MS) return null
        val timingAge = age(now, lastTiming, openedAt)
        if (lastTiming > 0L && timingAge >= TIMING_MS) return "timing silence ${timingAge}ms"
        return "idle ${quiet}ms without data or feedback"
    }

    fun samePeer(left: String, right: String): Boolean {
        if (left.isBlank() || right.isBlank()) return false
        return host(left) == host(right)
    }

    fun controlClosed(activeId: Long, closedId: Long, sessionOpen: Boolean): Boolean =
        sessionOpen && activeId != 0L && activeId == closedId

    /**
     * The first RTSP session connection stays the control socket.
     * A later short SETUP may close immediately and must not replace it.
     * After the session is marked closed, the next connection becomes the new one.
     */
    fun nextControl(current: Long, incoming: Long, sessionClosed: Boolean): Long {
        if (incoming == 0L) return current
        if (sessionClosed || current == 0L) return incoming
        return current
    }

    private fun age(now: Long, stamp: Long, openedAt: Long): Long =
        now - (if (stamp > 0L) stamp else openedAt)

    private fun host(value: String): String =
        value.substringBefore('%').removePrefix("::ffff:").lowercase()
}
