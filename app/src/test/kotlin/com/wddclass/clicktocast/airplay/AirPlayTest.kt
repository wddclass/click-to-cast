package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class AirPlayTest {
    @Test
    fun textXmlAndBinaryPlayBodies() {
        val text = AirPlayTexts.playCommand(
            null,
            "Content-Location: http://cdn.example/a.mp4\nStart-Position: 0.5\n".toByteArray(),
        )
        assertEquals("http://cdn.example/a.mp4", text?.url)
        assertEquals(0.5, text?.startFraction)

        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <plist version="1.0"><dict>
            <key>Content-Location</key><string>https://cdn.example/b.mp4</string>
            <key>Start-Position</key><real>0.1</real>
            </dict></plist>
        """.trimIndent()
        val fromXml = AirPlayTexts.playCommand(null, xml.toByteArray())
        assertEquals("https://cdn.example/b.mp4", fromXml?.url)
        assertEquals(0.1, fromXml?.startFraction ?: 0.0, 0.0001)

        val binary = Base64.getDecoder().decode(
            "YnBsaXN0MDDSAQIDBF8QEENvbnRlbnQtTG9jYXRpb25eU3RhcnQtUG9zaXRpb25fEBhodHRwOi8vY2RuLmV4YW1wbGUvYS5tcDQjP9AAAAAAAAAIDSAvSgAAAAAAAAEBAAAAAAAAAAUAAAAAAAAAAAAAAAAAAABT",
        )
        val fromBinary = AirPlayTexts.playCommand(null, binary)
        assertEquals("http://cdn.example/a.mp4", fromBinary?.url)
        assertEquals(0.25, fromBinary?.startFraction ?: 0.0, 0.0001)
        assertNull(AirPlayTexts.playCommand(null, ByteArray(0)))
    }

    @Test
    fun headerLocationWinsWhenTheBodyOmitsIt() {
        val command = AirPlayTexts.playCommand("http://cdn.example/from-header.mp4", ByteArray(0))
        assertEquals("http://cdn.example/from-header.mp4", command?.url)
        assertEquals(0.0, command?.startFraction)
    }

    @Test
    fun recordsUseAStableLocallyAdministeredMac() {
        val mac = AirPlayTexts.macFromUuid("11111111-2222-3333-4444-555555555555")
        val first = mac.substring(0, 2).toInt(16)
        assertEquals(0, first and 0x01)
        assertEquals(0x02, first and 0x02)
        assertEquals(17, mac.length)
        val txt = AirPlayTexts.airplayTxt(mac)
        assertEquals(AirPlayTexts.FEATURES, txt["features"])
        assertEquals(AirPlayTexts.PUBLIC_KEY, txt["pk"])
        assertEquals(mac, txt["deviceid"])
        assertEquals(36, AirPlayTexts.pi(mac).length)
        val raop = AirPlayTexts.raopInstanceName(mac, "投屏点我")
        assertEquals("${mac.replace(":", "")}@投屏点我", raop)
        assertTrue(AirPlayTexts.raopTxt(mac).containsKey("ft"))
        val info = AirPlayTexts.serverInfo(mac)
        assertTrue(info.contains("<key>deviceid</key><string>$mac</string>"))
        assertTrue(info.contains("srcvers"))
    }

    @Test
    fun httpPlayScrubRateAndStop() {
        val sink = FakeSink()
        val handler = AirPlayHandler({ AirPlayIdentity("投屏点我", "12:11:11:22:22:33") }, sink)
        assertNull(handler.handle(HttpRequest("GET", "/dmr/description.xml", emptyMap(), ByteArray(0), "10.0.0.8")))

        val play = handler.handle(
            HttpRequest(
                "POST",
                "/play",
                emptyMap(),
                "Content-Location: http://cdn.example/a.mp4\nStart-Position: 0\n".toByteArray(),
                "10.0.0.8",
            ),
        )
        assertEquals(200, play?.status)
        assertEquals("http://cdn.example/a.mp4", sink.url)
        assertTrue(sink.playing)

        val info = handler.handle(HttpRequest("GET", "/playback-info", emptyMap(), ByteArray(0), "10.0.0.8"))
        assertTrue(String(info!!.body).contains("<key>rate</key><real>1.000</real>"))

        val scrub = handler.handle(HttpRequest("GET", "/scrub", emptyMap(), ByteArray(0), "10.0.0.8"))
        assertTrue(String(scrub!!.body).startsWith("duration: "))

        val seek = handler.handle(HttpRequest("POST", "/scrub", emptyMap(), ByteArray(0), "10.0.0.8", "position=12.5"))
        assertEquals(200, seek?.status)
        assertEquals(12.5, sink.seconds, 0.001)

        val pause = handler.handle(HttpRequest("POST", "/rate", emptyMap(), ByteArray(0), "10.0.0.8", "value=0"))
        assertEquals(200, pause?.status)
        assertFalse(sink.playing)

        handler.handle(HttpRequest("POST", "/stop", emptyMap(), ByteArray(0), "10.0.0.8"))
        assertTrue(sink.stopped)

        val server = handler.handle(HttpRequest("GET", "/server-info", emptyMap(), ByteArray(0), "10.0.0.8"))
        assertEquals("text/x-apple-plist+xml", server?.headers?.get("Content-Type"))
        assertTrue(String(server!!.body).contains("12:11:11:22:22:33"))
    }

    private class FakeSink : AirPlaySink {
        var url = ""
        var playing = false
        var stopped = false
        var seconds = 0.0

        override fun play(url: String, startFraction: Double, userAgent: String) {
            this.url = url
            playing = true
            stopped = false
        }

        override fun stop() {
            playing = false
            stopped = true
        }

        override fun pause() {
            playing = false
        }

        override fun resume() {
            playing = true
        }

        override fun seekSeconds(seconds: Double) {
            this.seconds = seconds
        }

        override fun status(): AirPlayStatus = AirPlayStatus(
            positionSec = seconds,
            durationSec = 40.0,
            rate = if (playing) 1.0 else 0.0,
            ready = true,
        )
    }
}
