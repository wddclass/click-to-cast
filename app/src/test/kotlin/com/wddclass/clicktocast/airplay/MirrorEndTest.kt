package com.wddclass.clicktocast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorEndTest {
    @Test
    fun staysUpDuringGraceAndWhileEitherSignalIsFresh() {
        val opened = 10_000L
        assertNull(MirrorEnd.reason(10_000L, 0L, 10_000L, 10_000L, 10_000L))
        assertNull(MirrorEnd.reason(opened + 5_000L, opened, 0L, 0L, 0L))
        assertNull(MirrorEnd.reason(opened + 20_000L, opened, 0L, 0L, 0L))
        assertNull(MirrorEnd.reason(opened + 20_000L, opened, opened + 18_000L, 0L, 0L))
        assertNull(MirrorEnd.reason(opened + 20_000L, opened, opened + 1_000L, opened + 19_000L, opened + 1_000L))
    }

    @Test
    fun idleAndTimingSilenceNeedBothMediaPathsQuiet() {
        val opened = 10_000L
        val now = opened + 20_000L
        val quiet = opened + 10_000L
        assertEquals(
            "idle 10000ms without data or feedback",
            MirrorEnd.reason(now, opened, quiet, quiet, now),
        )
        assertEquals(
            "timing silence 19000ms",
            MirrorEnd.reason(now, opened, quiet, quiet, opened + 1_000L),
        )
        assertEquals(
            "idle 10000ms without data or feedback",
            MirrorEnd.reason(now, opened, quiet, quiet, 0L),
        )
    }

    @Test
    fun peerMatchStripsZoneAndMappedIpv4() {
        assertTrue(MirrorEnd.samePeer("192.168.123.8", "192.168.123.8"))
        assertTrue(MirrorEnd.samePeer("192.168.123.8%eth0", "192.168.123.8"))
        assertTrue(MirrorEnd.samePeer("::ffff:192.168.123.8", "192.168.123.8"))
        assertTrue(MirrorEnd.samePeer("FE80::1", "fe80::1%wlan0"))
        assertFalse(MirrorEnd.samePeer("192.168.123.8", "192.168.123.2"))
        assertFalse(MirrorEnd.samePeer("", "192.168.123.8"))
        assertFalse(MirrorEnd.samePeer("192.168.123.8", ""))
    }

    @Test
    fun onlyTheOpenControlConnectionEndsTheMirror() {
        assertTrue(MirrorEnd.controlClosed(4L, 4L, sessionOpen = true))
        assertFalse(MirrorEnd.controlClosed(0L, 4L, sessionOpen = true))
        assertFalse(MirrorEnd.controlClosed(4L, 9L, sessionOpen = true))
        assertFalse(MirrorEnd.controlClosed(4L, 4L, sessionOpen = false))
    }

    @Test
    fun laterSetupDoesNotReplaceTheControlConnection() {
        assertEquals(7L, MirrorEnd.nextControl(0L, 7L, sessionClosed = false))
        assertEquals(7L, MirrorEnd.nextControl(7L, 8L, sessionClosed = false))
        assertEquals(8L, MirrorEnd.nextControl(7L, 8L, sessionClosed = true))
        assertEquals(7L, MirrorEnd.nextControl(7L, 0L, sessionClosed = true))
    }
}
