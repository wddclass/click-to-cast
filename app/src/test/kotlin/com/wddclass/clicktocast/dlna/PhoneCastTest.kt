package com.wddclass.clicktocast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Socket

/**
 * Shapes taken from LAN captures of Bilibili, iQiyi, Youku, Tencent Video and BubbleUPnP.
 * Empty or broken CurrentURIMetaData must not turn a real media URL into UPnP 402.
 */
class PhoneCastTest {
    @Test
    fun bilibiliEmptyMetadataThenPlay() {
        val (player, renderer, handler) = stack()
        val logs = mutableListOf<String>()
        val previous = DmrLog.out
        DmrLog.out = { logs += it }
        try {
            val set = post(
                handler,
                "\"${Upnp.AVT}#SetAVTransportURI\"",
                """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><CurrentURI>http://upos-sz-mirrorcos.bilivideo.com/ok.mp4?deadline=1&amp;platform=android</CurrentURI><CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>
                """.trim(),
            )
            assertEquals(200, set.status)
            assertFalse(String(set.body, Charsets.UTF_8).contains(">402<"))
            assertEquals(
                "http://upos-sz-mirrorcos.bilivideo.com/ok.mp4?deadline=1&platform=android",
                player.request?.uri,
            )
            val play = post(handler, "\"${Upnp.AVT}#Play\"", playBody("1"))
            assertEquals(200, play.status)
            assertEquals("PLAYING", renderer.snapshot().transportState)
            assertTrue(logs.any { it.startsWith("soap ") && it.contains("SetAVTransportURI") && it.contains("HTTP 200") })
        } finally {
            DmrLog.out = previous
        }
    }

    @Test
    fun iqiyiUnquotedMixedCaseActionAndEscapedUri() {
        val (player, _, handler) = stack()
        val response = post(
            handler,
            "urn:schemas-upnp-org:service:AVTransport:1#setavtransporturi",
            """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <InstanceID>0</InstanceID>
            <CurrentURI>http://cache.m.iqiyi.com/a.mp4?t=1&amp;qd_sc=abc</CurrentURI>
            <CurrentURIMetaData></CurrentURIMetaData>
            </u:SetAVTransportURI>
            </s:Body>
            </s:Envelope>
            """.trim(),
        )
        assertEquals(200, response.status)
        assertTrue(String(response.body, Charsets.UTF_8).contains("SetAVTransportURIResponse"))
        assertEquals("http://cache.m.iqiyi.com/a.mp4?t=1&qd_sc=abc", player.request?.uri)
    }

    @Test
    fun youkuNotImplementedMetadataWithoutInstanceId() {
        val (player, renderer, handler) = stack()
        val early = post(handler, "\"${Upnp.AVT}#Play\"", "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:Play xmlns:u=\"${Upnp.AVT}\"><Speed>1</Speed></u:Play></s:Body></s:Envelope>")
        assertEquals(500, early.status)
        assertTrue(String(early.body, Charsets.UTF_8).contains(">701<"))

        val set = post(
            handler,
            "${Upnp.AVT}#SetAVTransportURI",
            """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body>
            <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
            <CurrentURI>http://pl.youku.com/file.mp4</CurrentURI>
            <CurrentURIMetaData>NOT_IMPLEMENTED</CurrentURIMetaData>
            </u:SetAVTransportURI>
            </s:Body>
            </s:Envelope>
            """.trim(),
        )
        assertEquals(200, set.status)
        assertEquals("http://pl.youku.com/file.mp4", player.request?.uri)
        val play = post(handler, "'${Upnp.AVT}#Play'", playBody("1"))
        assertEquals(200, play.status)
        assertEquals("PLAYING", renderer.snapshot().transportState)
    }

    @Test
    fun tencentDidlWithRawAmpersandStillSetsUri() {
        val (player, _, handler) = stack()
        val body = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <s:Body>
                <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI>http://vv.video.qq.com/play.mp4?vid=abc&amp;fmt=mp4</CurrentURI>
                  <CurrentURIMetaData><DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="0" parentID="-1" restricted="1"><dc:title>庆余年 & 续集</dc:title><res protocolInfo="http-get:*:video/mp4:*">http://vv.video.qq.com/play.mp4</res></item></DIDL-Lite></CurrentURIMetaData>
                </u:SetAVTransportURI>
              </s:Body>
            </s:Envelope>
        """.trim()
        val response = post(handler, "\"${Upnp.AVT}#SetAVTransportURI\"", body)
        assertEquals(200, response.status)
        assertFalse(String(response.body, Charsets.UTF_8).contains(">402<"))
        assertEquals("http://vv.video.qq.com/play.mp4?vid=abc&fmt=mp4", player.request?.uri)
    }

    @Test
    fun selfClosingMetadataAndNoPrefix() {
        val (player, _, handler) = stack()
        val response = post(
            handler,
            "\"${Upnp.AVT}#SetAVTransportURI\"",
            """
            <Envelope xmlns="http://schemas.xmlsoap.org/soap/envelope/">
              <Body>
                <SetAVTransportURI xmlns="urn:schemas-upnp-org:service:AVTransport:1">
                  <InstanceID>0</InstanceID>
                  <CurrentURI><![CDATA[http://cdn.example/movie.mp4?a=1&b=2]]></CurrentURI>
                  <CurrentURIMetaData/>
                </SetAVTransportURI>
              </Body>
            </Envelope>
            """.trim(),
        )
        assertEquals(200, response.status)
        assertEquals("http://cdn.example/movie.mp4?a=1&b=2", player.request?.uri)
    }

    @Test
    fun emptyUriIsStill402() {
        val (_, _, handler) = stack()
        val response = post(
            handler,
            "\"${Upnp.AVT}#SetAVTransportURI\"",
            """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:SetAVTransportURI xmlns:u="${Upnp.AVT}"><InstanceID>0</InstanceID><CurrentURI></CurrentURI><CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI>
            </s:Body></s:Envelope>
            """.trim(),
        )
        assertEquals(500, response.status)
        assertTrue(String(response.body, Charsets.UTF_8).contains(">402<"))
    }

    @Test
    fun bubbleChunkedExpect100Continue() {
        val (player, renderer, handler) = stack()
        val server = MiniHttpServer { handler.handle(it) }
        val port = server.start(0)
        try {
            val soap = """
                <?xml version="1.0" encoding="utf-8"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><InstanceID>0</InstanceID><CurrentURI>http://192.168.1.9/bubble.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData></u:SetAVTransportURI></s:Body></s:Envelope>
            """.trim()
            val head = "POST /dmr/control/avt HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "SoApAcTiOn: \"${Upnp.AVT}#SetAVTransportURI\"\r\n" +
                "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Expect: 100-continue\r\n" +
                "Connection: close\r\n\r\n"
            val set = exchange(port, head, chunked(soap))
            assertTrue(set.startsWith("HTTP/1.1 100 Continue"))
            assertTrue(set.contains("HTTP/1.1 200"))
            assertTrue(set.contains("SetAVTransportURIResponse"))
            assertEquals("http://192.168.1.9/bubble.mp4", player.request?.uri)

            val playHead = "POST /dmr/control/avt HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\n" +
                "soapaction: \"${Upnp.AVT}#Play\"\r\n" +
                "Content-Type: text/xml; charset=\"utf-8\"\r\n" +
                "Content-Length: ${playBody("1").toByteArray(Charsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n"
            val play = exchange(port, playHead + playBody("1"), null)
            assertTrue(play.contains("HTTP/1.1 200"))
            assertEquals("PLAYING", renderer.snapshot().transportState)
        } finally {
            server.stop()
        }
    }

    @Test
    fun descriptionIconAndProtocolList() {
        val (_, renderer, handler) = stack()
        val description = handler.handle(HttpRequest("GET", DmrPaths.DESCRIPTION, emptyMap(), ByteArray(0), "10.0.0.2"))
        val xml = String(description.body, Charsets.UTF_8)
        assertTrue(xml.contains("<friendlyName>投屏点我</friendlyName>"))
        assertTrue(xml.contains("<presentationURL>http://127.0.0.1:8192/</presentationURL>"))
        assertTrue(xml.contains("<mimetype>image/png</mimetype>"))
        assertFalse(xml.contains("redsonic"))
        assertFalse(xml.contains("hpplay"))
        val icon = handler.handle(HttpRequest("GET", DmrPaths.ICON_48, emptyMap(), ByteArray(0), "10.0.0.2"))
        assertEquals(200, icon.status)
        assertEquals("image/png", icon.headers["Content-Type"])
        assertTrue(icon.body.size > 8)
        assertEquals(0x89.toByte(), icon.body[0])
        assertEquals('P'.code.toByte(), icon.body[1])
        val protocols = renderer.handleCm("GetProtocolInfo", emptyMap())
        val sink = protocols.body
        for (token in listOf(
            "http-get:*:video/mp4",
            "video/x-flv",
            "application/x-mpegURL",
            "application/vnd.apple.mpegurl",
            "video/MP2T",
            "audio/mpeg",
            "http-get:*:*:*",
        )) {
            assertTrue(token, sink.contains(token))
        }
    }

    private fun stack(): Triple<FakePlayer, DlnaRenderer, RendererHttpHandler> {
        val player = FakePlayer()
        lateinit var renderer: DlnaRenderer
        val events = GenaPublisher(RecordingTransport(), ManualScheduler(), properties = { renderer.eventProperties(it) })
        renderer = DlnaRenderer(player, { events.publish(it) }, { "TestAgent/1.0" })
        val handler = RendererHttpHandler(
            renderer,
            { DeviceProfile(Upnp.DEFAULT_FRIENDLY_NAME, "abc", 8192, "127.0.0.1", "0.1.0") },
            events,
        )
        return Triple(player, renderer, handler)
    }

    private fun post(handler: RendererHttpHandler, soapAction: String, body: String): HttpResponse {
        return handler.handle(
            HttpRequest(
                "POST",
                DmrPaths.CONTROL_AVT,
                mapOf("soapaction" to soapAction, "content-type" to "text/xml; charset=\"utf-8\""),
                body.toByteArray(Charsets.UTF_8),
                "192.168.1.33",
            ),
        )
    }

    private fun playBody(speed: String) = """
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
          <s:Body>
            <u:Play xmlns:u="${Upnp.AVT}"><InstanceID>0</InstanceID><Speed>$speed</Speed></u:Play>
          </s:Body>
        </s:Envelope>
    """.trim()

    private fun exchange(port: Int, head: String, payloadAfterContinue: ByteArray?): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 4_000
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write(head.toByteArray(Charsets.ISO_8859_1))
            output.flush()
            if (payloadAfterContinue == null) return readMessage(input)
            val interim = readMessage(input)
            output.write(payloadAfterContinue)
            output.flush()
            return interim + readMessage(input)
        }
    }

    private fun readMessage(input: InputStream): String {
        val header = ByteArrayOutputStream()
        var previous = 0
        while (true) {
            val value = input.read()
            if (value < 0) break
            header.write(value)
            val size = header.size()
            if (previous == '\r'.code && value == '\n'.code && size >= 4) {
                val bytes = header.toByteArray()
                val end = bytes.size
                if (bytes[end - 4] == '\r'.code.toByte() && bytes[end - 3] == '\n'.code.toByte() &&
                    bytes[end - 2] == '\r'.code.toByte() && bytes[end - 1] == '\n'.code.toByte()
                ) {
                    break
                }
            }
            previous = value
        }
        val headText = String(header.toByteArray(), Charsets.ISO_8859_1)
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(headText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return headText + String(body, 0, offset, Charsets.UTF_8)
    }

    private fun chunked(body: String): ByteArray {
        val payload = body.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        val split = if (payload.size < 2) payload.size else payload.size / 2
        fun writeChunk(offset: Int, len: Int) {
            if (len == 0) return
            out.write("${len.toString(16)}\r\n".toByteArray(Charsets.US_ASCII))
            out.write(payload, offset, len)
            out.write("\r\n".toByteArray(Charsets.US_ASCII))
        }
        writeChunk(0, split)
        writeChunk(split, payload.size - split)
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        return out.toByteArray()
    }
}
