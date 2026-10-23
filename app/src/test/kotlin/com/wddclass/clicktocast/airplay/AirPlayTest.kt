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
        val pk = AirPlayKeys.fromSeed(ByteArray(32) { 7 }).publicKeyHex
        val txt = AirPlayTexts.airplayTxt(mac, pk)
        assertEquals("0x5A7FFFF7,0x1E", AirPlayTexts.FEATURES)
        assertEquals(130367356919L, AirPlayTexts.FEATURES_INT)
        val low = AirPlayTexts.FEATURES_INT and 0xFFFFFFFFL
        assertEquals(0x80L, low and 0x80L)
        assertEquals(0x04L, low and 0x04L)
        assertEquals(AirPlayTexts.FEATURES, txt["features"])
        assertEquals(pk, txt["pk"])
        assertEquals(64, pk.length)
        assertEquals(mac, txt["deviceid"])
        assertEquals(36, AirPlayTexts.pi(mac).length)
        val raop = AirPlayTexts.raopInstanceName(mac, "投屏点我")
        assertEquals("${mac.replace(":", "")}@投屏点我", raop)
        assertTrue(AirPlayTexts.raopTxt(mac, pk).containsKey("ft"))
        val info = AirPlayTexts.serverInfo(mac)
        assertTrue(info.contains("<key>deviceid</key><string>$mac</string>"))
        assertTrue(info.contains("srcvers"))
    }

    @Test
    fun httpPlayScrubRateAndStop() {
        val sink = FakeSink()
        val keys = AirPlayKeys.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val handler = AirPlayHandler(
            { AirPlayIdentity("投屏点我", "12:11:11:22:22:33", keys.publicKeyHex) },
            sink,
            keys,
        )
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

        val deviceInfo = handler.handle(
            HttpRequest(
                "GET",
                "/info",
                mapOf(
                    "cseq" to "0",
                    "user-agent" to "AirPlay/425.1",
                    "x-apple-protocolversion" to "1",
                ),
                ByteArray(0),
                "192.168.123.8",
                protocol = "RTSP/1.0",
            ),
        )
        assertEquals(200, deviceInfo?.status)
        assertEquals("application/x-apple-binary-plist", deviceInfo?.headers?.get("Content-Type"))
        assertEquals("0", deviceInfo?.headers?.get("CSeq"))
        val parsed = BinaryPlist.top(deviceInfo!!.body) as Map<*, *>
        assertEquals("12:11:11:22:22:33", parsed["deviceID"])
        assertEquals(AirPlayTexts.FEATURES_INT, parsed["features"])
        assertEquals("AppleTV3,2", parsed["model"])
        assertEquals(AirPlayTexts.SRCVERS, parsed["sourceVersion"])
        assertEquals(4L, parsed["statusFlags"])
        assertEquals("投屏点我", parsed["name"])
        val pk = parsed["pk"] as ByteArray
        assertTrue(pk.contentEquals(keys.edPublic))
        val txt = String(parsed["txtAirPlay"] as ByteArray, Charsets.ISO_8859_1)
        assertTrue(txt.contains("features=${AirPlayTexts.FEATURES}"))
        assertTrue(txt.contains("pk=${keys.publicKeyHex}"))
        assertTrue(txt.contains("model=AppleTV3,2"))
        assertTrue(txt.contains("deviceid=12:11:11:22:22:33"))
        val wire = String(deviceInfo.toBytes("RTSP/1.0"), Charsets.ISO_8859_1)
        assertTrue(wire.startsWith("RTSP/1.0 200 OK\r\n"))
        assertTrue(wire.contains("CSeq: 0\r\n"))
        assertFalse(wire.contains("Connection: close"))

        val denied = handler.handle(HttpRequest("POST", "/pair-setup", emptyMap(), byteArrayOf(1), "10.0.0.9"))
        assertEquals(400, denied?.status)
        val verify = handler.handle(HttpRequest("POST", "/pair-verify", emptyMap(), byteArrayOf(1), "10.0.0.9"))
        assertEquals(403, verify?.status)
        val fairplay = handler.handle(
            HttpRequest("POST", "/fp-setup", emptyMap(), ByteArray(16), "192.168.123.8", protocol = "RTSP/1.0"),
        )
        assertEquals(400, fairplay?.status)

        val upgraded = handler.handle(
            HttpRequest(
                "POST",
                "/reverse",
                mapOf("upgrade" to "PTTH/1.0", "connection" to "Upgrade"),
                ByteArray(0),
                "192.168.123.8",
            ),
        )
        assertEquals(101, upgraded?.status)
        assertEquals("Upgrade", upgraded?.headers?.get("Connection"))
    }

    @Test
    fun legacyPairSetupThenVerify() {
        val server = AirPlayKeys.fromSeed(ByteArray(32) { (it + 1).toByte() })
        val client = AirPlayKeys.fromSeed(ByteArray(32) { (it + 9).toByte() })
        val handler = AirPlayHandler(
            { AirPlayIdentity("投屏点我", "12:11:11:22:22:33", server.publicKeyHex) },
            FakeSink(),
            server,
        )
        val setup = handler.handle(
            HttpRequest(
                "POST",
                "/pair-setup",
                mapOf("cseq" to "1", "content-type" to "application/octet-stream"),
                client.edPublic,
                "192.168.123.8",
                protocol = "RTSP/1.0",
            ),
        )
        assertEquals(200, setup?.status)
        assertTrue(server.edPublic.contentEquals(setup!!.body))
        assertTrue(String(setup.toBytes("RTSP/1.0"), Charsets.ISO_8859_1).startsWith("RTSP/1.0 200 OK"))

        val eph = client.newX25519()
        val ephPub = eph.generatePublicKey().encoded
        val first = byteArrayOf(1, 0, 0, 0) + ephPub + client.edPublic
        val step1 = handler.handle(
            HttpRequest("POST", "/pair-verify", mapOf("cseq" to "2"), first, "192.168.123.8", protocol = "RTSP/1.0"),
        )
        assertEquals(200, step1?.status)
        assertEquals(96, step1!!.body.size)
        val serverX = step1.body.copyOf(32)
        val encrypted = step1.body.copyOfRange(32, 96)
        val shared = AirPlayKeys.sharedSecret(eph, serverX)
        val aesKey = AirPlayKeys.sha512("Pair-Verify-AES-Key", shared).copyOf(16)
        val aesIv = AirPlayKeys.sha512("Pair-Verify-AES-IV", shared).copyOf(16)
        val serverSig = AirPlayKeys.ctr(aesKey, aesIv, 0, encrypted)
        assertTrue(AirPlayKeys.verify(server.edPublic, serverX + ephPub, serverSig))

        val clientSig = client.sign(ephPub + serverX)
        val second = byteArrayOf(0, 0, 0, 0) + AirPlayKeys.ctr(aesKey, aesIv, 64, clientSig)
        val step2 = handler.handle(
            HttpRequest("POST", "/pair-verify", mapOf("cseq" to "3"), second, "192.168.123.8", protocol = "RTSP/1.0"),
        )
        assertEquals(200, step2?.status)
        assertEquals(0, step2!!.body.size)

        val play = handler.handle(
            HttpRequest(
                "POST",
                "/play",
                mapOf("cseq" to "4", "content-type" to "text/parameters"),
                "Content-Location: http://cdn.example/after-verify.mp4\nStart-Position: 0\n".toByteArray(),
                "192.168.123.8",
                protocol = "RTSP/1.0",
            ),
        )
        assertEquals(200, play?.status)

        val setupStream = handler.handle(
            HttpRequest(
                "SETUP",
                "/stream",
                mapOf("cseq" to "5"),
                ByteArray(0),
                "192.168.123.8",
                protocol = "RTSP/1.0",
            ),
        )
        assertEquals(200, setupStream?.status)

        val forged = second.copyOf()
        forged[forged.size - 1] = (forged[forged.size - 1].toInt() xor 0xFF).toByte()
        handler.handle(
            HttpRequest("POST", "/pair-verify", emptyMap(), first, "192.168.123.9", protocol = "RTSP/1.0"),
        )
        val rejected = handler.handle(
            HttpRequest("POST", "/pair-verify", emptyMap(), forged, "192.168.123.9", protocol = "RTSP/1.0"),
        )
        assertEquals(403, rejected?.status)
    }

    @Test
    fun fpSetupThenMirrorSetup() {
        val phase1 = hex("46504c590301010000000004020002bb")
        assertEquals(16, phase1.size)
        val keys = AirPlayKeys.fromSeed(ByteArray(32) { 3 })
        val handler = AirPlayHandler(
            { AirPlayIdentity("投屏点我", "12:11:11:22:22:33", keys.publicKeyHex) },
            FakeSink(),
            keys,
            eventPort = { 8192 },
        )
        val first = handler.handle(
            HttpRequest("POST", "/fp-setup", mapOf("cseq" to "4"), phase1, "192.168.123.8", protocol = "RTSP/1.0"),
        )
        assertEquals(200, first?.status)
        assertEquals(142, first!!.body.size)
        assertEquals(0x02, first.body[13].toInt() and 0xFF)

        val phase2 = ByteArray(164)
        phase1.copyInto(phase2)
        phase2[6] = 3
        for (index in 144 until 164) phase2[index] = (index - 100).toByte()
        val second = handler.handle(
            HttpRequest("POST", "/fp-setup", mapOf("cseq" to "5"), phase2, "192.168.123.8", protocol = "RTSP/1.0"),
        )
        assertEquals(200, second?.status)
        assertEquals(32, second!!.body.size)
        assertTrue(second.body.copyOfRange(12, 32).contentEquals(phase2.copyOfRange(144, 164)))

        val setupBody = BinaryPlist.write(
            linkedMapOf(
                "ekey" to ByteArray(72) { 1 },
                "streams" to listOf(linkedMapOf<String, Any>("type" to 110, "streamConnectionID" to 7L)),
            ),
        )
        val setup = handler.handle(
            HttpRequest(
                "SETUP",
                "/stream",
                mapOf("cseq" to "6", "content-type" to "application/x-apple-binary-plist"),
                setupBody,
                "192.168.123.8",
                protocol = "RTSP/1.0",
            ),
        )
        assertEquals(200, setup?.status)
        assertEquals("application/x-apple-binary-plist", setup?.headers?.get("Content-Type"))
        val parsed = BinaryPlist.top(setup!!.body) as Map<*, *>
        assertEquals(8192L, parsed["eventPort"])
        assertTrue((parsed["timingPort"] as Long) > 0L)
        val streams = parsed["streams"] as List<*>
        val stream = streams[0] as Map<*, *>
        assertEquals(110L, stream["type"])
        assertEquals(7L, stream["streamConnectionID"])
        assertTrue((stream["dataPort"] as Long) > 0L)

        val record = handler.handle(
            HttpRequest("RECORD", "/stream", mapOf("cseq" to "7"), ByteArray(0), "192.168.123.8", protocol = "RTSP/1.0"),
        )
        assertEquals(200, record?.status)
        val play = handler.handle(
            HttpRequest(
                "POST",
                "/play",
                emptyMap(),
                "Content-Location: http://cdn.example/after-setup.mp4\nStart-Position: 0\n".toByteArray(),
                "192.168.123.8",
                protocol = "RTSP/1.0",
            ),
        )
        assertEquals(200, play?.status)
    }

    private fun hex(text: String): ByteArray {
        val out = ByteArray(text.length / 2)
        for (index in out.indices) {
            out[index] = text.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    @Test
    fun mdnsAnnounceAnswersAndGoodbye() {
        val pk = AirPlayKeys.fromSeed(ByteArray(32) { 4 }).publicKeyHex
        val ad = Mdns.advertisement("投屏点我", "AA:BB:CC:DD:EE:FF", 8192, "192.168.123.2", pk)
        val packets = Mdns.announce(ad)
        assertEquals(2, packets.size)
        val airplay = packets[0]
        assertTrue(String(airplay, Charsets.ISO_8859_1).contains("_airplay"))
        assertTrue(indexOf(airplay, byteArrayOf(192.toByte(), 168.toByte(), 123, 2)) >= 0)
        assertTrue(indexOf(airplay, byteArrayOf(0x20, 0x00)) >= 0)
        val liveTtls = Mdns.recordTtls(airplay)
        assertTrue(liveTtls.isNotEmpty())
        assertTrue(liveTtls.any { it > 0L })

        val gone = Mdns.announce(ad, goodbye = true)
        assertTrue(gone.all { packet ->
            val ttls = Mdns.recordTtls(packet)
            ttls.isNotEmpty() && ttls.all { it == 0L }
        })

        val query = Mdns.query(0x1234, "_airplay._tcp.local")
        val reply = Mdns.response(query, ad)
        assertEquals(0x12, reply!![0].toInt() and 0xFF)
        assertEquals(0x34, reply[1].toInt() and 0xFF)
        assertEquals(0x84, reply[2].toInt() and 0xFF)
        assertTrue(String(reply, Charsets.ISO_8859_1).contains("_airplay"))
        assertNull(Mdns.response(Mdns.query(7, "_googlecast._tcp.local"), ad))
        assertNull(Mdns.response(airplay, ad))

        val compressed = compressedQuery(0x0A0B)
        val compressedReply = Mdns.response(compressed, ad)
        assertEquals(0x0A, compressedReply!![0].toInt() and 0xFF)
        assertEquals(0x0B, compressedReply[1].toInt() and 0xFF)
        assertEquals(0x84, compressedReply[2].toInt() and 0xFF)
        assertEquals(listOf("_airplay._tcp.local"), Mdns.questionNames(compressed))
    }

    private fun compressedQuery(id: Int): ByteArray {
        val name = byteArrayOf(
            8, '_'.code.toByte(), 'a'.code.toByte(), 'i'.code.toByte(), 'r'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'y'.code.toByte(),
            4, '_'.code.toByte(), 't'.code.toByte(), 'c'.code.toByte(), 'p'.code.toByte(),
            5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(), 'a'.code.toByte(), 'l'.code.toByte(),
            0,
        )
        val packet = ByteArray(18 + name.size)
        packet[0] = (id ushr 8).toByte()
        packet[1] = id.toByte()
        packet[5] = 1
        packet[12] = 0xC0.toByte()
        packet[13] = 0x12
        packet[14] = 0x00
        packet[15] = 12
        packet[16] = 0x00
        packet[17] = 1
        name.copyInto(packet, 18)
        return packet
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > haystack.size) return -1
        for (start in 0..haystack.size - needle.size) {
            var same = true
            for (offset in needle.indices) {
                if (haystack[start + offset] != needle[offset]) {
                    same = false
                    break
                }
            }
            if (same) return start
        }
        return -1
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
