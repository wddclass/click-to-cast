package com.wddclass.clicktocast.dlna

import com.wddclass.clicktocast.ui.LiveChrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackChromeTest {
    @Test
    fun formatsDecimalMegabytesAndKilobytes() {
        assertEquals("2.3 MB/s", NetSpeed.format(2_300_000))
        assertEquals("1.0 MB/s", NetSpeed.format(1_000_000))
        assertEquals("2.3 MB/s", NetSpeed.format(2_340_000))
        assertEquals("512 KB/s", NetSpeed.format(512_000))
        assertEquals("0 KB/s", NetSpeed.format(0))
        assertEquals("0 KB/s", NetSpeed.format(-5))
    }

    @Test
    fun metadataLiveOnlyForUnsetDuration() {
        assertTrue(LiveSignals.metadataDeclaresLive("""<res duration="NOT_IMPLEMENTED"></res>"""))
        assertTrue(LiveSignals.metadataDeclaresLive("""<res duration="0"></res>"""))
        assertTrue(LiveSignals.metadataDeclaresLive("""<res duration="0:00:00"></res>"""))
        assertTrue(LiveSignals.metadataDeclaresLive("""<duration>0.0</duration>"""))
        assertFalse(LiveSignals.metadataDeclaresLive("""<res duration="00:01:30"></res>"""))
        assertFalse(LiveSignals.metadataDeclaresLive("""<item><dc:title>示例</dc:title></item>"""))
        assertFalse(LiveSignals.metadataDeclaresLive(""))
    }

    @Test
    fun engineLiveIgnoresStartupAndClosedHls() {
        assertFalse(LiveSignals.fromEngine(false, false, false, -1, false))
        assertTrue(LiveSignals.fromEngine(false, false, false, -1, true))
        assertFalse(LiveSignals.fromEngine(false, true, false, -1, true))
        assertTrue(LiveSignals.fromEngine(false, true, true, 50_000, true))
        assertTrue(LiveSignals.fromEngine(true, true, false, 50_000, true))
        assertFalse(LiveSignals.fromEngine(false, false, false, 90_000, true))
    }

    @Test
    fun combineKeepsEngineLiveAndDropsStaleMetadata() {
        assertTrue(LiveSignals.combine(true, false, 50_000))
        assertTrue(LiveSignals.combine(false, true, -1))
        assertTrue(LiveSignals.combine(false, true, 0))
        assertFalse(LiveSignals.combine(false, true, 90_000))
        assertFalse(LiveSignals.combine(false, false, -1))
    }

    @Test
    fun liveBadgeShowsOnlyWithControls() {
        assertTrue(LiveChrome.badgeVisible(live = true, controlsVisible = true))
        assertFalse(LiveChrome.badgeVisible(live = true, controlsVisible = false))
        assertFalse(LiveChrome.badgeVisible(live = false, controlsVisible = true))
        assertFalse(LiveChrome.badgeVisible(live = false, controlsVisible = false))
    }
}
