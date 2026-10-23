package com.wddclass.clicktocast.dlna

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackMessagesTest {
    @Test
    fun expiredUrlsAskForANewCast() {
        assertEquals(PlaybackMessages.EXPIRED, PlaybackMessages.describe(2004, 401))
        assertEquals(PlaybackMessages.EXPIRED, PlaybackMessages.describe(2004, 403))
        assertEquals(PlaybackMessages.EXPIRED, PlaybackMessages.describe(2004, 410))
    }

    @Test
    fun otherFailuresStayDistinct() {
        assertEquals("视频地址无法打开", PlaybackMessages.describe(2004, 404))
        assertEquals("视频地址无法打开", PlaybackMessages.describe(2005, null))
        assertEquals("网络连接失败", PlaybackMessages.describe(2001, null))
        assertEquals("网络连接失败", PlaybackMessages.describe(2002, null))
        assertEquals("这部电视解不了这个视频", PlaybackMessages.describe(4001, null))
        assertEquals("视频格式无法识别", PlaybackMessages.describe(3001, null))
        assertEquals("播放失败", PlaybackMessages.describe(1000, null))
    }
}
