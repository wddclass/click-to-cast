package com.wddclass.clicktocast.prefs

import org.junit.Assert.assertEquals
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

    @Test
    fun replayingTheSameUrlRefreshesTheExistingRow() {
        val older = record("https://cdn.example/a.mp4").copy(at = 10L, session = 1L, title = "旧")
        val other = record("https://cdn.example/b.mp4").copy(at = 20L, session = 2L)
        val replay = record("https://cdn.example/a.mp4").copy(at = 30L, session = 3L, title = "新")
        val merged = mergeHistory(listOf(other, older), replay)
        assertEquals(2, merged.size)
        assertEquals(30L, merged[0].at)
        assertEquals("新", merged[0].title)
        assertEquals(3L, merged[0].session)
        assertEquals("https://cdn.example/b.mp4", merged[1].uri)
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
