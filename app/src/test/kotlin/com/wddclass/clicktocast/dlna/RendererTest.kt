package com.wddclass.clicktocast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class FakePlayer : PlayerBackend {
    var request: PlaybackRequest? = null
    var position: Long = 5_000
    var duration: Long = 90_000
    val seeks = mutableListOf<Long>()
    private var snap = PlaybackSnapshot()

    override fun load(request: PlaybackRequest) {
        this.request = request
        position = request.startPositionMs
        snap = PlaybackSnapshot(EnginePlayback.IDLE, position, duration)
    }

    override fun play() {
        snap = snap.copy(playback = EnginePlayback.READY_PLAY, positionMs = position)
    }

    override fun pause() {
        snap = snap.copy(playback = EnginePlayback.READY_PAUSE, positionMs = position)
    }

    override fun stop() {
        snap = snap.copy(playback = EnginePlayback.IDLE, positionMs = position)
    }

    override fun seekTo(positionMs: Long) {
        position = positionMs
        seeks += positionMs
        snap = snap.copy(positionMs = position)
    }

    override fun snapshot(): PlaybackSnapshot = snap.copy(positionMs = position, durationMs = duration)

    fun fail(message: String) {
        snap = snap.copy(playback = EnginePlayback.ERROR, error = message)
    }
}

class ManualScheduler : Scheduler {
    private val tasks = ArrayDeque<() -> Unit>()
    override fun schedule(delayMs: Long, task: () -> Unit) {
        tasks.add(task)
    }

    fun flush() {
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()
    }
}

class RecordingTransport : EventTransport {
    val calls = mutableListOf<String>()
    override fun notify(callbackUrl: String, sid: String, seq: Long, body: String) {
        calls += "$seq $sid $body"
    }
}

class DlnaRendererTest {
    private val player = FakePlayer()
    private val transport = RecordingTransport()
    private val scheduler = ManualScheduler()
    private val renderer: DlnaRenderer
    private val events: GenaPublisher

    init {
        lateinit var created: DlnaRenderer
        events = GenaPublisher(transport, scheduler, properties = { service -> created.eventProperties(service) })
        created = DlnaRenderer(player, { events.publish(it) }, { "TestAgent/1.0" })
        renderer = created
    }

    @Test
    fun playPauseSeekStopAndVolume() {
        val set = renderer.handleAv(
            "SetAVTransportURI",
            mapOf(
                "InstanceID" to "0",
                "CurrentURI" to "http://cdn.example/video.mp4?token=secret",
                "CurrentURIMetaData" to "<DIDL-Lite><item><dc:title xmlns:dc=\"http://purl.org/dc/elements/1.1/\">示例</dc:title></item></DIDL-Lite>",
            ),
            "Bilibili/1.0 (iPhone)",
        )
        assertEquals(200, set.httpStatus)
        assertEquals("示例", player.request?.title)
        assertEquals("已收到片源", renderer.snapshot().statusLine)
        assertFalse(renderer.snapshot().showPlayer)

        assertEquals(200, renderer.handleAv("Play", mapOf("InstanceID" to "0", "Speed" to "1"), null).httpStatus)
        assertEquals("PLAYING", renderer.snapshot().transportState)
        assertTrue(renderer.snapshot().showPlayer)
        assertTrue(renderer.snapshot().sourceLine.contains("iPhone"))

        player.position = 5_000
        val position = renderer.handleAv("GetPositionInfo", mapOf("InstanceID" to "0"), null)
        assertTrue(position.body.contains("<RelTime>00:00:05</RelTime>"))
        assertTrue(position.body.contains("<TrackDuration>00:01:30</TrackDuration>"))

        assertEquals(200, renderer.handleAv("Pause", emptyMap(), null).httpStatus)
        assertEquals("PAUSED_PLAYBACK", renderer.snapshot().transportState)

        assertEquals(200, renderer.handleAv("Seek", mapOf("Unit" to "REL_TIME", "Target" to "00:00:15"), null).httpStatus)
        assertEquals(listOf(15_000L), player.seeks)

        assertEquals(500, renderer.handleAv("Seek", mapOf("Unit" to "TRACK_NR", "Target" to "1"), null).httpStatus)
        assertEquals(500, renderer.handleAv("Play", mapOf("InstanceID" to "5", "Speed" to "1"), null).httpStatus)
        assertEquals(500, renderer.handleAv("Play", mapOf("Speed" to "2"), null).httpStatus)

        assertEquals(200, renderer.handleRc("SetVolume", mapOf("DesiredVolume" to "120")).httpStatus)
        assertEquals(100, renderer.snapshot().volume)
        assertEquals(200, renderer.handleRc("SetMute", mapOf("DesiredMute" to "1")).httpStatus)
        assertTrue(renderer.snapshot().muted)
        val mute = renderer.handleRc("GetMute", emptyMap())
        assertTrue(mute.body.contains(">1<"))

        assertEquals(200, renderer.handleAv("Stop", emptyMap(), null).httpStatus)
        assertEquals("STOPPED", renderer.snapshot().transportState)
        assertFalse(renderer.snapshot().showPlayer)
        assertEquals(200, renderer.handleCm("GetProtocolInfo", emptyMap()).httpStatus)
    }

    @Test
    fun playWithoutMediaIsRejected() {
        val result = renderer.handleAv("Play", mapOf("InstanceID" to "0", "Speed" to "1"), null)
        assertEquals(500, result.httpStatus)
        assertTrue(result.body.contains(">701<"))
    }

    @Test
    fun subscribeSendsInitialLastChange() {
        val result = events.subscribe(Upnp.AVT, "<http://127.0.0.1:9/evt>", "Second-300", null)
        assertTrue(result!!.sid.startsWith("uuid:"))
        assertEquals(300, result.timeoutSeconds)
        scheduler.flush()
        assertEquals(1, transport.calls.size)
        assertTrue(transport.calls[0].contains("NO_MEDIA_PRESENT") || transport.calls[0].contains("LastChange"))
        assertTrue(events.unsubscribe(result.sid))
        assertFalse(events.unsubscribe(result.sid))
    }
}

class RendererHttpTest {
    @Test
    fun soapAndDescriptionRoundTrip() {
        val player = FakePlayer()
        lateinit var renderer: DlnaRenderer
        val events = GenaPublisher(RecordingTransport(), ManualScheduler(), properties = { renderer.eventProperties(it) })
        renderer = DlnaRenderer(player, { events.publish(it) }, { "UA" })
        var port = 0
        val server = MiniHttpServer { request ->
            val profile = DeviceProfile(Upnp.DEFAULT_FRIENDLY_NAME, "abc", port, "127.0.0.1", "0.1.0")
            RendererHttpHandler(renderer, { profile }, events).handle(request)
        }
        port = server.start(0)
        try {
            val description = http("GET", "http://127.0.0.1:$port/dmr/description.xml")
            assertEquals(200, description.first)
            assertTrue(description.second.contains("<friendlyName>投屏点我</friendlyName>"))
            assertFalse(description.second.contains("客厅"))

            val soap = """
                <?xml version="1.0"?>
                <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
                  <s:Body>
                    <u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
                      <InstanceID>0</InstanceID>
                      <CurrentURI>http://cdn/a.mp4</CurrentURI>
                      <CurrentURIMetaData></CurrentURIMetaData>
                    </u:SetAVTransportURI>
                  </s:Body>
                </s:Envelope>
            """.trimIndent()
            val set = http(
                "POST",
                "http://127.0.0.1:$port/dmr/control/avt",
                soap,
                mapOf("SOAPAction" to "\"${Upnp.AVT}#SetAVTransportURI\""),
            )
            assertEquals(200, set.first)
            assertTrue(set.second.contains("SetAVTransportURIResponse"))
            val play = http(
                "POST",
                "http://127.0.0.1:$port/dmr/control/avt",
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:Play xmlns:u="${Upnp.AVT}"><InstanceID>0</InstanceID><Speed>1</Speed></u:Play></s:Body></s:Envelope>""",
                mapOf("SOAPAction" to "\"${Upnp.AVT}#Play\""),
            )
            assertEquals(200, play.first)
            assertEquals("PLAYING", renderer.snapshot().transportState)

            val subscribed = socket(
                port,
                "SUBSCRIBE /dmr/event/avt HTTP/1.1\r\n" +
                    "HOST: 127.0.0.1:$port\r\n" +
                    "CALLBACK: <http://127.0.0.1:9/callback>\r\n" +
                    "NT: upnp:event\r\n" +
                    "TIMEOUT: Second-1800\r\n" +
                    "CONNECTION: close\r\n\r\n",
            )
            assertTrue(subscribed.startsWith("HTTP/1.1 200"))
            assertTrue(subscribed.contains("SID: uuid:"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun snifferDetectsMislabeledHls() {
        val server = MiniHttpServer { request ->
            if (request.path.endsWith("/a.mp4")) {
                HttpResponse(
                    200,
                    "OK",
                    mapOf("Content-Type" to "video/mp4"),
                    "#EXTM3U\n#EXTINF:1,\nseg.ts\n".toByteArray(),
                )
            } else {
                HttpResponse(404, "Not Found")
            }
        }
        val port = server.start(0)
        try {
            val kind = StreamSniffer.sniff("http://127.0.0.1:$port/a.mp4", "Test", emptyMap())
            assertEquals(MediaKind.HLS, kind)
        } finally {
            server.stop()
        }
    }

    private fun http(
        method: String,
        url: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Triple<Int, String, Map<String, String>> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3000
            readTimeout = 3000
            instanceFollowRedirects = false
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray()) }
            }
        }
        val code = connection.responseCode
        val text = (if (code >= 400) connection.errorStream else connection.inputStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val responseHeaders = connection.headerFields
            .filterKeys { it != null }
            .mapKeys { it.key.lowercase() }
            .mapValues { it.value.joinToString(",") }
        connection.disconnect()
        return Triple(code, text, responseHeaders)
    }

    private fun socket(port: Int, raw: String): String {
        java.net.Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 3000
            val output = socket.getOutputStream()
            output.write(raw.toByteArray(Charsets.UTF_8))
            output.flush()
            return socket.getInputStream().bufferedReader().use { it.readText() }
        }
    }
}
