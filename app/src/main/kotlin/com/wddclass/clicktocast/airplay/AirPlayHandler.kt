package com.wddclass.clicktocast.airplay

import com.wddclass.clicktocast.dlna.DlnaRenderer
import com.wddclass.clicktocast.dlna.DmrLog
import com.wddclass.clicktocast.dlna.HttpRequest
import com.wddclass.clicktocast.dlna.HttpResponse
import com.wddclass.clicktocast.dlna.redactUrl

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
        val response = dispatch(request) ?: return null
        DmrLog.i("airplay ${request.method} ${request.path} from ${request.remoteIp} -> HTTP ${response.status}")
        return response
    }

    private fun dispatch(request: HttpRequest): HttpResponse? = when (request.path) {
        "/info" -> if (request.method == "GET") info() else null
        "/server-info" -> if (request.method == "GET") xml(AirPlayTexts.serverInfo(identity().deviceId)) else null
        "/playback-info" -> if (request.method == "GET") xml(AirPlayTexts.playbackInfo(sink.status())) else null
        "/pair-setup", "/pair-verify", "/pair-add" -> refuse(request)
        "/play" -> if (request.method == "POST") play(request) else null
        "/stop" -> if (request.method == "POST" || request.method == "GET") stop() else null
        "/rate" -> if (request.method == "POST" || request.method == "GET") rate(request) else null
        "/scrub" -> if (request.method == "POST" || request.method == "GET") scrub(request) else null
        "/reverse" -> if (request.method == "POST") reverse(request) else null
        else -> null
    }

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
        return xml(AirPlayTexts.info(who.name, who.deviceId))
    }

    private fun refuse(request: HttpRequest): HttpResponse {
        DmrLog.i(
            "airplay ${request.path} from ${request.remoteIp} refused; pairing and FairPlay mirroring are not implemented",
        )
        return empty(501, "Not Implemented")
    }

    private fun reverse(request: HttpRequest): HttpResponse {
        DmrLog.i("airplay reverse from ${request.remoteIp} left closed; video URL playback does not use it")
        return empty(200, "OK")
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
}
