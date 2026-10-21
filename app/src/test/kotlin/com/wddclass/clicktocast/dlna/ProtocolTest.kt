package com.wddclass.clicktocast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FriendlyNameTest {
    @Test
    fun defaultNameIsExactlyTheProductName() {
        assertEquals("投屏点我", Upnp.DEFAULT_FRIENDLY_NAME)
        assertFalse(Upnp.DEFAULT_FRIENDLY_NAME.contains("客厅"))
        assertFalse(Upnp.DEFAULT_FRIENDLY_NAME.contains("卧室"))
    }

    @Test
    fun descriptionAdvertisesTheDefaultName() {
        val xml = DescriptionXml.device(sample(Upnp.DEFAULT_FRIENDLY_NAME))
        assertTrue(xml.contains("<friendlyName>投屏点我</friendlyName>"))
        assertFalse(xml.contains("客厅"))
        assertFalse(xml.contains("redsonic"))
        assertFalse(xml.contains("hpplay"))
        assertTrue(xml.contains("<UDN>uuid:11111111-2222-3333-4444-555555555555</UDN>"))
        assertTrue(xml.contains("<presentationURL>http://192.168.1.8:8192/</presentationURL>"))
        assertTrue(xml.contains("<mimetype>image/png</mimetype>"))
        assertTrue(xml.contains("http://192.168.1.8:8192/dmr/icon48.png"))
        assertTrue(xml.contains("http://192.168.1.8:8192/dmr/icon120.png"))
        assertTrue(xml.contains(DmrPaths.CONTROL_AVT))
        assertTrue(xml.contains(Upnp.AVT))
    }

    @Test
    fun blankNameFallsBackToTheDefault() {
        val xml = DescriptionXml.device(sample("  "))
        assertTrue(xml.contains("<friendlyName>投屏点我</friendlyName>"))
    }

    @Test
    fun renamedDeviceUsesTheSavedName() {
        val xml = DescriptionXml.device(sample("书房"))
        assertTrue(xml.contains("<friendlyName>书房</friendlyName>"))
        assertTrue(xml.contains("<modelName>投屏点我</modelName>"))
    }

    @Test
    fun friendlyNameIsEscaped() {
        val xml = DescriptionXml.device(sample("A&B<C"))
        assertTrue(xml.contains("<friendlyName>A&amp;B&lt;C</friendlyName>"))
    }

    private fun sample(name: String) = DeviceProfile(
        friendlyName = name,
        uuid = "11111111-2222-3333-4444-555555555555",
        httpPort = 8192,
        hostAddress = "192.168.1.8",
        version = "0.1.0",
    )
}

class DlnaTimeTest {
    @Test
    fun formatsHoursMinutesSeconds() {
        assertEquals("00:00:00", DlnaTime.format(0))
        assertEquals("00:01:30", DlnaTime.format(90_000))
        assertEquals("01:02:03", DlnaTime.format(3_723_000))
        assertEquals("100:00:00", DlnaTime.format(100L * 3_600_000))
        assertEquals("00:00:00", DlnaTime.format(-1))
    }

    @Test
    fun parsesAbsoluteAndRelative() {
        assertEquals(15_000L, DlnaTime.parse("00:00:15"))
        assertEquals(62_500L, DlnaTime.parse("00:01:02.5"))
        assertEquals(20_000L, DlnaTime.parse("+00:00:10", currentMs = 10_000))
        assertEquals(5_000L, DlnaTime.parse("-00:00:05", currentMs = 10_000))
        assertEquals(0L, DlnaTime.parse("-00:01:00", currentMs = 1_000))
        assertNull(DlnaTime.parse("NOT_IMPLEMENTED"))
        assertNull(DlnaTime.parse("nope"))
    }
}

class SoapCodecTest {
    @Test
    fun readsActionFromHeaderOrBody() {
        val body = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>http://example.com/a.mp4</CurrentURI>
                  <CurrentURIMetaData></CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trimIndent()
        assertEquals(
            "SetAVTransportURI",
            SoapCodec.actionName("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", body),
        )
        assertEquals("SetAVTransportURI", SoapCodec.actionName(null, body))
        val args = SoapCodec.arguments(body)
        assertEquals("http://example.com/a.mp4", SoapCodec.arg(args, "currenturi"))
    }

    @Test
    fun successAndFaultShapes() {
        val ok = SoapResult.ok(Upnp.AVT, "Play", linkedMapOf("Speed" to "1"))
        assertEquals(200, ok.httpStatus)
        assertTrue(ok.body.contains("PlayResponse"))
        assertTrue(ok.body.contains(">1<"))
        val fault = SoapResult.fault(718, "Invalid InstanceID")
        assertEquals(500, fault.httpStatus)
        assertTrue(fault.body.contains("<errorCode>718</errorCode>"))
    }
}

class CastMetadataTest {
    @Test
    fun readsTitleMimeAndHeaders() {
        val didl = """
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/">
              <item>
                <dc:title>示例视频</dc:title>
                <res protocolInfo="http-get:*:application/vnd.apple.mpegurl:*">http://cdn/a.m3u8</res>
                <desc>
                  <userAgent>CustomAgent/2</userAgent>
                  <header name="Referer">https://www.bilibili.com</header>
                </desc>
              </item>
            </DIDL-Lite>
        """.trimIndent()
        val parsed = CastMetadata.parse(didl)
        assertEquals("示例视频", parsed.title)
        assertEquals("application/vnd.apple.mpegurl", parsed.mimeType)
        assertEquals("CustomAgent/2", parsed.userAgent)
        assertEquals("https://www.bilibili.com", parsed.headers["Referer"])
    }

    @Test
    fun dropsHeaderInjection() {
        val didl = """
            <DIDL-Lite>
              <header name="X-Token">abc&#10;Host: evil</header>
              <header name="Bad Name">nope</header>
            </DIDL-Lite>
        """.trimIndent()
        val parsed = CastMetadata.parse(didl)
        assertEquals("abcHost: evil", parsed.headers["X-Token"])
        assertFalse(parsed.headers.containsKey("Bad Name"))
    }

    @Test
    fun titleThatIsAUrlDoesNotKeepTheQuery() {
        assertEquals("movie.mp4", CastMetadata.safeTitle("http://cdn/movie.mp4?token=secret", "http://cdn/movie.mp4?token=secret"))
    }
}

class MediaKindDetectorTest {
    @Test
    fun sniffBeatsAMisleadingName() {
        val playlist = "#EXTM3U\n#EXTINF:1,a\nhttp://cdn/a.ts".toByteArray()
        assertEquals(MediaKind.HLS, MediaKindDetector.detect("http://cdn/a.mp4", "video/mp4", playlist))
        assertEquals(MediaKind.DASH, MediaKindDetector.detect("http://cdn/a.mp4", null, "<MPD profiles=\"urn:mpeg:dash:profile:isoff-live:2011\"></MPD>".toByteArray()))
        assertEquals(MediaKind.FLV, MediaKindDetector.detect("http://cdn/a", null, byteArrayOf(0x46, 0x4c, 0x56, 0x01)))
        val mp4 = ByteArray(12)
        "ftyp".toByteArray(Charsets.US_ASCII).copyInto(mp4, 4)
        assertEquals(MediaKind.PROGRESSIVE, MediaKindDetector.detect("http://cdn/a", null, mp4))
    }

    @Test
    fun extensionsAndContentTypes() {
        assertEquals(MediaKind.HLS, MediaKindDetector.detect("http://cdn/live.m3u8", null, null))
        assertEquals(MediaKind.DASH, MediaKindDetector.detect("http://cdn/manifest.mpd", null, null))
        assertEquals(MediaKind.FLV, MediaKindDetector.detect("http://cdn/a.flv", null, null))
        assertEquals(MediaKind.HLS, MediaKindDetector.detect("http://cdn/a", "application/vnd.apple.mpegurl", null))
    }
}

class SenderLabelTest {
    @Test
    fun iphoneBilibiliLine() {
        val line = SenderLabel.sourceLine(
            "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)",
            "",
            "https://upos.bilivideo.com/video.m3u8",
        )
        assertEquals("来自 iPhone · bilibili", line)
    }

    @Test
    fun unknownControllerStaysGeneric() {
        assertEquals("来自 局域网设备", SenderLabel.sourceLine(null, "", "http://192.168.1.9/a.mp4"))
    }
}

class SsdpMessagesTest {
    private val profile = DeviceProfile(
        friendlyName = Upnp.DEFAULT_FRIENDLY_NAME,
        uuid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
        httpPort = 8192,
        hostAddress = "192.168.1.20",
        version = "0.1.0",
    )

    @Test
    fun mediaRendererSearchReturnsOneResponse() {
        val packet = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 1
            ST: urn:schemas-upnp-org:device:MediaRenderer:1

        """.trimIndent().replace("\n", "\r\n")
        val search = SsdpMessages.parseMSearch(packet)
        assertEquals(Upnp.DEVICE, search?.st)
        val responses = SsdpMessages.searchResponses(profile, search!!.st, "Mon, 01 Jan 2024 00:00:00 GMT", 1, 1)
        assertEquals(1, responses.size)
        assertTrue(responses[0].contains("LOCATION: http://192.168.1.20:8192/dmr/description.xml"))
        assertTrue(responses[0].contains("ST: ${Upnp.DEVICE}"))
        assertTrue(responses[0].contains("USN: uuid:${profile.uuid}::${Upnp.DEVICE}"))
    }

    @Test
    fun ssdpAllCoversRootServicesAndV2() {
        val all = SsdpMessages.matching("ssdp:all", profile.uuid)
        assertEquals(10, all.size)
        assertEquals(10, SsdpMessages.matching("\"ssdp:all\"", profile.uuid).size)
        assertTrue(all.any { it.first == Upnp.DEVICE })
        assertTrue(all.any { it.first == Upnp.DEVICE_V2 })
        assertTrue(all.any { it.first == Upnp.AVT })
        assertTrue(all.any { it.first == Upnp.AVT_V2 })
        assertEquals(6, SsdpMessages.alive(profile, "Mon, 01 Jan 2024 00:00:00 GMT", 1, 1).size)
        val v2 = SsdpMessages.searchResponses(profile, Upnp.DEVICE_V2, "Mon, 01 Jan 2024 00:00:00 GMT", 1, 1)
        assertEquals(1, v2.size)
        assertTrue(v2[0].contains("ST: ${Upnp.DEVICE_V2}"))
        assertTrue(v2[0].contains("USN: uuid:${profile.uuid}::${Upnp.DEVICE_V2}"))
        assertTrue(v2[0].contains("ClickToCast"))
        assertFalse(v2[0].contains("redsonic"))
        assertFalse(v2[0].contains("hpplay"))
        assertTrue(SsdpMessages.matching("urn:schemas-upnp-org:device:MediaServer:1", profile.uuid).isEmpty())
        assertEquals(0, SsdpTiming.delayMs(0, 99))
        assertEquals(400, SsdpTiming.delayMs(1, 400))
        assertEquals(1000, SsdpTiming.delayMs(1, 9_000))
        assertEquals(3, SsdpTiming.ALIVE_BURST)
        assertEquals(30_000L, SsdpTiming.ALIVE_INTERVAL_MS)
        assertEquals(2, SsdpTiming.MULTICAST_TTL)
    }
}

class NetworkAddressTest {
    @Test
    fun phoneSubnetWinsOverTheFallback() {
        assertEquals(
            "192.168.8.20",
            com.wddclass.clicktocast.net.NetworkAddresses.preferredHost(
                "192.168.8.55",
                listOf("10.0.0.8", "192.168.8.20"),
                "10.0.0.8",
            ),
        )
        assertTrue(com.wddclass.clicktocast.net.NetworkAddresses.same24("192.168.1.8", "192.168.1.22"))
        assertFalse(com.wddclass.clicktocast.net.NetworkAddresses.same24("192.168.1.8", "192.168.2.22"))
    }
}
