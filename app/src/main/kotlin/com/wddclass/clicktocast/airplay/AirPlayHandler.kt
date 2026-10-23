package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.DlnaRenderer
import com.wddclass.clicktocast.dlna.DmrLog
import com.wddclass.clicktocast.dlna.HttpRequest
import com.wddclass.clicktocast.dlna.HttpResponse
import com.wddclass.clicktocast.dlna.redactUrl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

interface AirPlaySink {
    fun play(url: String, startFraction: Double, userAgent: String)
    fun stop()
    fun pause()
    fun resume()
    fun seekSeconds(seconds: Double)
    fun status(): AirPlayStatus
}

class RendererAirPlaySink(private val renderer: DlnaRenderer) : AirPlaySink {
    override fun play(url: String, startFraction: Double, userAgent: String) {
        renderer.castUrl(url, userAgent, startFraction)
    }

    override fun stop() {
        renderer.stopPlayback()
    }

    override fun pause() {
        renderer.pausePlayback()
    }

    override fun resume() {
        renderer.resumePlayback()
    }

    override fun seekSeconds(seconds: Double) {
        renderer.seekSeconds(seconds)
    }

    override fun status(): AirPlayStatus {
        val snap = renderer.snapshot()
        return AirPlayStatus(
            positionSec = snap.positionMs.coerceAtLeast(0) / 1000.0,
            durationSec = if (snap.durationMs > 0) snap.durationMs / 1000.0 else 0.0,
            rate = if (snap.playing) 1.0 else 0.0,
            ready = snap.durationMs > 0 || snap.playing || snap.transportState == "PAUSED_PLAYBACK",
        )
    }
}

/**
 * HTTP surface for AirPlay video URL playback.
 * Paths that are not part of this flow return null so the DLNA handler can run.
 */
class AirPlayHandler(
    private val identity: () -> AirPlayIdentity,
    private val sink: AirPlaySink,
) {
    fun handle(request: HttpRequest): HttpResponse? {
        val response = dispatch(request)
        if (response == null && !shouldLog(request)) return null
        val line = response?.let { "${protocolName(request)} ${it.status}" } ?: "not handled"
        DmrLog.i(
            "airplay ${request.protocol} ${request.method} ${request.path} from ${request.remoteIp} " +
                "${headerSummary(request)} -> $line",
        )
        return response?.let { decorate(request, it) }
    }

    private fun dispatch(request: HttpRequest): HttpResponse? = when (request.path) {
        "/info" -> if (request.method == "GET" || request.method == "POST") info() else null
        "/server-info" -> if (request.method == "GET") xml(AirPlayTexts.serverInfo(identity().deviceId)) else null
        "/playback-info" -> if (request.method == "GET") xml(AirPlayTexts.playbackInfo(sink.status())) else null
        "/pair-setup", "/pair-verify", "/pair-add" -> refuse(request)
        "/fp-setup" -> fairplay(request)
        "/feedback" -> if (request.method == "POST" || request.method == "GET") empty(200, "OK") else null
        "*", "/options" -> if (request.method == "OPTIONS") options() else null
        "/play" -> if (request.method == "POST") play(request) else null
        "/stop" -> if (request.method == "POST" || request.method == "GET") stop() else null
        "/rate" -> if (request.method == "POST" || request.method == "GET") rate(request) else null
        "/scrub" -> if (request.method == "POST" || request.method == "GET") scrub(request) else null
        "/reverse" -> if (request.method == "POST" || request.method == "GET") reverse(request) else null
        else -> null
    }

    private fun shouldLog(request: HttpRequest): Boolean {
        if (request.path.startsWith("/dmr")) return false
        if (request.protocol.startsWith("RTSP", ignoreCase = true)) return true
        if (request.header("user-agent")?.contains("AirPlay", ignoreCase = true) == true) return true
        if (request.header("x-apple-protocolversion") != null) return true
        if (request.header("x-apple-session-id") != null) return true
        return request.path.startsWith("/pair") || request.path == "/fp-setup" || request.path == "/feedback"
    }

    private fun headerSummary(request: HttpRequest): String {
        val parts = ArrayList<String>()
        for (name in LOGGED_HEADERS) {
            val value = request.header(name) ?: continue
            parts += "$name=${value.take(80)}"
        }
        if (request.body.isNotEmpty()) parts += "bytes=${request.body.size}"
        return parts.joinToString(" ")
    }

    private fun protocolName(request: HttpRequest): String =
        if (request.protocol.startsWith("RTSP", ignoreCase = true)) "RTSP" else "HTTP"

    private fun play(request: HttpRequest): HttpResponse {
        val command = AirPlayTexts.playCommand(request.header("content-location"), request.body)
        if (command == null) {
            DmrLog.i("airplay play rejected from ${request.remoteIp} bytes=${request.body.size}")
            return text(400, "Bad Request", "missing Content-Location\n")
        }
        val agent = request.header("user-agent")?.takeIf { it.isNotBlank() } ?: "AirPlay Darwin"
        DmrLog.i(
            "airplay play from ${request.remoteIp} url=${redactUrl(command.url)} start=${command.startFraction}",
        )
        sink.play(command.url, command.startFraction, agent)
        return empty(200, "OK")
    }

    private fun stop(): HttpResponse {
        sink.stop()
        return empty(200, "OK")
    }

    private fun rate(request: HttpRequest): HttpResponse {
        val value = AirPlayTexts.queryValue(request.query, "value")?.toDoubleOrNull()
            ?: AirPlayTexts.queryValue(request.body.decodeToString(), "value")?.toDoubleOrNull()
        val paused = value != null && value <= 0.0
        if (paused) sink.pause() else sink.resume()
        return empty(200, "OK")
    }

    private fun scrub(request: HttpRequest): HttpResponse {
        val position = AirPlayTexts.queryValue(request.query, "position")?.toDoubleOrNull()
            ?: textPosition(request.body.decodeToString())
        if (position != null) {
            sink.seekSeconds(position)
            return empty(200, "OK")
        }
        return text(200, "OK", AirPlayTexts.scrubText(sink.status()), "text/parameters")
    }

    private fun info(): HttpResponse {
        val who = identity()
        return HttpResponse(
            200,
            "OK",
            linkedMapOf("Content-Type" to "application/x-apple-binary-plist"),
            AirPlayTexts.infoPlist(who.name, who.deviceId),
        )
    }

    private fun fairplay(request: HttpRequest): HttpResponse {
        val phase = if (request.body.size > 6) request.body[6].toInt() and 0xFF else -1
        DmrLog.i(
            "airplay fp-setup from ${request.remoteIp} phase=$phase bytes=${request.body.size}; " +
                "FairPlay mirroring is not implemented",
        )
        return empty(501, "Not Implemented")
    }

    private fun options(): HttpResponse = HttpResponse(
        200,
        "OK",
        linkedMapOf(
            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET",
        ),
    )

    private fun refuse(request: HttpRequest): HttpResponse {
        DmrLog.i(
            "airplay ${request.path} from ${request.remoteIp} bytes=${request.body.size}; " +
                "pairing is not implemented, video URL /play does not need it",
        )
        return empty(501, "Not Implemented")
    }

    private fun reverse(request: HttpRequest): HttpResponse {
        val upgrade = request.header("upgrade")?.contains("PTTH", ignoreCase = true) == true
        DmrLog.i(
            "airplay reverse from ${request.remoteIp} upgrade=$upgrade purpose=${request.header("x-apple-purpose") ?: "-"}",
        )
        if (!upgrade) return empty(200, "OK")
        return HttpResponse(
            101,
            "Switching Protocols",
            linkedMapOf(
                "Upgrade" to "PTTH/1.0",
                "Connection" to "Upgrade",
            ),
        )
    }

    private fun decorate(request: HttpRequest, response: HttpResponse): HttpResponse {
        val headers = linkedMapOf<String, String>()
        response.headers.forEach { (key, value) -> headers[key] = value }
        val cseq = request.header("cseq")
        if (!cseq.isNullOrBlank() && headers.keys.none { it.equals("CSeq", true) }) headers["CSeq"] = cseq
        if (headers.keys.none { it.equals("Server", true) }) {
            headers["Server"] = "AirTunes/${AirPlayTexts.SRCVERS}"
        }
        if (request.protocol.startsWith("RTSP", ignoreCase = true)) {
            if (headers.keys.none { it.equals("Audio-Jack-Status", true) }) {
                headers["Audio-Jack-Status"] = "Connected; type=digital"
            }
            if (headers.keys.none { it.equals("Session", true) }) headers["Session"] = "CAFEBABE"
            if (headers.keys.none { it.equals("Date", true) }) headers["Date"] = httpDate()
        }
        return HttpResponse(response.status, response.reason, headers, response.body)
    }

    private fun httpDate(): String {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        return format.format(Date())
    }

    private fun textPosition(body: String): Double? {
        for (line in body.split('\n')) {
            val index = line.indexOf(':')
            if (index <= 0) continue
            if (line.substring(0, index).trim() == "position") {
                return line.substring(index + 1).trim().toDoubleOrNull()
            }
        }
        return null
    }

    private fun xml(body: String): HttpResponse = HttpResponse(
        200,
        "OK",
        linkedMapOf("Content-Type" to "text/x-apple-plist+xml"),
        body.toByteArray(Charsets.UTF_8),
    )

    private fun empty(status: Int, reason: String): HttpResponse =
        HttpResponse(status, reason, linkedMapOf("Content-Length" to "0"))

    private fun text(status: Int, reason: String, body: String, type: String = "text/plain"): HttpResponse =
        HttpResponse(status, reason, linkedMapOf("Content-Type" to type), body.toByteArray(Charsets.UTF_8))

    companion object {
        private val LOGGED_HEADERS = listOf(
            "user-agent",
            "content-type",
            "cseq",
            "x-apple-protocolversion",
            "x-apple-session-id",
            "upgrade",
            "connection",
            "content-length",
            "x-apple-purpose",
        )
    }
}
