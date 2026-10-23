package com.wddclass.clicktocast.prefs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryReplayTest {
    @Test
    fun replayNeedsAStoredHttpUrl() {
        assertTrue(record("https://cdn.example/a.mp4").canReplay)
        assertTrue(record("http://cdn.example/a.mp4").canReplay)
        assertFalse(record("").canReplay)
        assertFalse(record("file:///tmp/a.mp4").canReplay)
        assertFalse(record("rtsp://10.0.0.2/live").canReplay)
    }

    private fun record(uri: String) = CastRecord(
        at = 1L,
        title = "片段",
        source = "来自 iPhone",
        quality = "1080p",
        outcome = "已接收",
        session = 4L,
        uri = uri,
        userAgent = "Mozilla/5.0",
        headers = mapOf("Referer" to "https://www.bilibili.com"),
    )
}
