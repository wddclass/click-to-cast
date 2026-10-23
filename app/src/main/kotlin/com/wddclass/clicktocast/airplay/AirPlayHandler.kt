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
    private val keys: AirPlayKeys,
    private val eventPort: () -> Int = { 8192 },
    private val mirror: MirrorSession? = null,
) {
    private val pairing = AirPlayPairing(keys)
    private val ports = AirPlayPorts(mirror)
    private var fairPlayMessage: ByteArray? = null
    private var sessionKey: ByteArray? = null
    private var sessionIv: ByteArray? = null
    private var sessionPath: String = ""
    private var clientTimingPort: Int = 0
    fun handle(request: HttpRequest): HttpResponse? {
        touchMirror(request)
        val response = dispatch(request)
        if (response == null && !shouldLog(request)) return null
        val line = response?.let { "${protocolName(request)} ${it.status} body=${it.body.size}" } ?: "not handled"
        DmrLog.i(
            "airplay ${request.protocol} ${request.method} ${request.path} from ${request.remoteIp} " +
                "verified=${pairing.isVerified(request.remoteIp)} ${headerSummary(request)} -> $line",
        )
        return response?.let { decorate(request, it) }
    }

    private fun dispatch(request: HttpRequest): HttpResponse? {
        when (request.method) {
            "SETUP" -> return setup(request)
            "RECORD" -> return record(request)
            "GET_PARAMETER" -> return parameter(request, read = true)
            "SET_PARAMETER" -> return parameter(request, read = false)
            "FLUSH" -> return rtspSimple(request, "flush")
            "TEARDOWN" -> return teardown(request)
            "ANNOUNCE" -> return rtspSimple(request, "announce")
        }
        return dispatchPath(request)
    }

    private fun dispatchPath(request: HttpRequest): HttpResponse? = when (request.path) {
        "/info" -> if (request.method == "GET" || request.method == "POST") info(request) else null
        "/server-info" -> if (request.method == "GET") xml(AirPlayTexts.serverInfo(identity().deviceId)) else null
        "/playback-info" -> if (request.method == "GET") xml(AirPlayTexts.playbackInfo(sink.status())) else null
        "/pair-setup" -> if (request.method == "POST") pairSetup(request) else null
        "/pair-verify" -> if (request.method == "POST") pairVerify(request) else null
        "/pair-add", "/pair-pin-start" -> if (request.method == "POST") acknowledge(request) else null
        "/fp-setup" -> fairplay(request)
        "/feedback" -> if (request.method == "POST" || request.method == "GET") empty(200, "OK") else null
        "*", "/options" -> if (request.method == "OPTIONS") options() else null
        "/play" -> if (request.method == "POST") play(request) else null
        "/stop" -> if (request.method == "POST" || request.method == "GET") stop() else null
        "/rate" -> if (request.method == "POST" || request.method == "GET") rate(request) else null
        "/scrub" -> if (request.method == "POST" || request.method == "GET") scrub(request) else null
        "/reverse" -> if (request.method == "POST" || request.method == "GET") reverse(request) else null
        else -> if (isAirPlayTransport(request)) unsupported(request) else null
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
        if (request.body.isNotEmpty()) {
            parts += "bytes=${request.body.size}"
            parts += "hex=${AirPlayKeys.hexPrefix(request.body, 24)}"
        }
        return parts.joinToString(" ")
    }

    private fun isAirPlayTransport(request: HttpRequest): Boolean {
        if (request.path.startsWith("/dmr")) return false
        if (request.protocol.startsWith("RTSP", ignoreCase = true)) return true
        if (request.header("user-agent")?.contains("AirPlay", ignoreCase = true) == true) return true
        if (request.header("x-apple-session-id") != null) return true
        if (request.header("x-apple-protocolversion") != null) return true
        return false
    }

    private fun unsupported(request: HttpRequest): HttpResponse {
        DmrLog.i(
            "airplay no handler for ${request.method} ${request.path} from ${request.remoteIp} " +
                "verified=${pairing.isVerified(request.remoteIp)} hex=${AirPlayKeys.hexPrefix(request.body, 24)}; " +
                "fp-setup, SETUP and mirror decrypt are implemented",
        )
        return empty(501, "Not Implemented")
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
            "airplay play from ${request.remoteIp} verified=${pairing.isVerified(request.remoteIp)} " +
                "url=${redactUrl(command.url)} start=${command.startFraction}",
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

    private fun info(request: HttpRequest): HttpResponse {
        val who = identity()
        val qualifier = infoQualifier(request)
        val body = if (qualifier == "txtAirPlay" || qualifier == "txtRAOP") {
            AirPlayTexts.infoTxtOnly(qualifier, who.deviceId, keys.edPublic)
        } else {
            AirPlayTexts.infoPlist(who.name, who.deviceId, keys.edPublic)
        }
        val keys = (runCatching { BinaryPlist.top(body) as? Map<*, *> }.getOrNull())?.keys?.joinToString(",")
        DmrLog.i(
            "airplay info from ${request.remoteIp} path=${request.path} qualifier=${qualifier ?: "-"} " +
                "bytes=${body.size} keys=$keys",
        )
        return HttpResponse(
            200,
            "OK",
            linkedMapOf("Content-Type" to "application/x-apple-binary-plist"),
            body,
        )
    }

    private fun infoQualifier(request: HttpRequest): String? {
        val type = request.header("content-type") ?: return null
        if (!type.contains("apple-binary-plist", ignoreCase = true)) return null
        val root = plistDictionary(request.body) ?: return null
        val qualifier = root["qualifier"] as? List<*> ?: return null
        return qualifier.firstOrNull() as? String
    }

    private fun pairSetup(request: HttpRequest): HttpResponse {
        val hkp = request.header("x-apple-hkp") ?: "-"
        DmrLog.i(
            "airplay pair-setup from ${request.remoteIp} hkp=$hkp bytes=${request.body.size} " +
                "hex=${AirPlayKeys.hexPrefix(request.body, 32)}",
        )
        val reply = pairing.pairSetup(request.remoteIp, request.body)
        if (reply == null) {
            DmrLog.i("airplay pair-setup rejected: expected 32-byte Ed25519 public key, not TLV/SRP")
            return empty(400, "Bad Request")
        }
        DmrLog.i("airplay pair-setup ok, replied with 32-byte Ed25519 public key")
        return octets(reply)
    }

    private fun pairVerify(request: HttpRequest): HttpResponse {
        DmrLog.i(
            "airplay pair-verify from ${request.remoteIp} bytes=${request.body.size} " +
                "hex=${AirPlayKeys.hexPrefix(request.body, 8)}",
        )
        val reply = pairing.pairVerify(request.remoteIp, request.body)
        if (reply == null) {
            DmrLog.i("airplay pair-verify failed from ${request.remoteIp}")
            return empty(403, "Forbidden")
        }
        if (reply.isEmpty()) {
            DmrLog.i(
                "airplay pair-verify complete from ${request.remoteIp}; " +
                    "next may be fp-setup, then SETUP or plaintext POST /play",
            )
        } else {
            DmrLog.i("airplay pair-verify step1 ok from ${request.remoteIp} reply=${reply.size}")
        }
        return octets(reply)
    }

    private fun acknowledge(request: HttpRequest): HttpResponse {
        DmrLog.i("airplay ${request.path} from ${request.remoteIp} bytes=${request.body.size}")
        return empty(200, "OK")
    }

    private fun fairplay(request: HttpRequest): HttpResponse {
        val seq = if (request.body.size > 6) request.body[6].toInt() and 0xFF else -1
        val mode = if (request.body.size > 14) request.body[14].toInt() and 0xFF else -1
        DmrLog.i(
            "airplay fp-setup from ${request.remoteIp} seq=$seq mode=$mode bytes=${request.body.size} " +
                "hex=${AirPlayKeys.hexPrefix(request.body, 16)}",
        )
        val reply = FairPlaySetup.reply(request.body)
        if (reply == null) {
            DmrLog.i("airplay fp-setup rejected: wanted FPLY version 3, 16-byte phase 1 or 164-byte phase 2")
            return empty(400, "Bad Request")
        }
        if (request.body.size >= 164) {
            fairPlayMessage = request.body.copyOf(164)
            DmrLog.i("airplay fp-setup phase 2 reply=${reply.size}; keymsg=164 stored for playfair_decrypt")
        } else {
            fairPlayMessage = null
            DmrLog.i("airplay fp-setup phase 1 mode=$mode reply=${reply.size}")
        }
        return octets(reply)
    }

    private fun setup(request: HttpRequest): HttpResponse {
        ports.ensure()
        val parsed = plistDictionary(request.body)
        if (parsed == null) {
            DmrLog.i(
                "airplay SETUP from ${request.remoteIp} not a binary plist bytes=${request.body.size} " +
                    "timing=${ports.timingPort} data=${ports.dataPort}",
            )
            return HttpResponse(
                200,
                "OK",
                linkedMapOf(
                    "Transport" to
                        "RTP/AVP/UDP;unicast;mode=record;server_port=${ports.dataPort};control_port=${ports.timingPort}",
                ),
            )
        }
        val ekey = parsed["ekey"] as? ByteArray
        val eiv = parsed["eiv"] as? ByteArray
        val message = fairPlayMessage
        val clientTiming = number(parsed["timingPort"])?.toInt() ?: 0
        if (clientTiming > 0) clientTimingPort = clientTiming
        if (eiv != null) sessionIv = eiv.copyOf()
        val clientName = parsed["name"] as? String
        if (!clientName.isNullOrBlank()) mirror?.noteClientName(clientName)
        DmrLog.i(
            "airplay SETUP from ${request.remoteIp} path=${request.path} " +
                "ekey=${ekey?.size ?: 0} eiv=${eiv?.size ?: 0} keymsg=${message?.size ?: 0} " +
                "clientTiming=$clientTiming timing=${ports.timingPort} data=${ports.dataPort} " +
                "audio=${ports.audioPort} plist=${describePlist(parsed)}",
        )
        if (ekey != null) {
            sessionKey = unwrapKey(ekey, message)
            val secret = pairing.ecdhSecret(request.remoteIp)
            DmrLog.i(
                "airplay ecdh ${if (secret == null) "absent" else "present"} " +
                    "playfair=${sessionKey?.size ?: 0} stored for the stream SETUP",
            )
        }
        if (sessionPath.isNotEmpty() && sessionPath != request.path) {
            DmrLog.i(
                "airplay SETUP path ${request.path} reuses keys from $sessionPath " +
                    "(a later TCP connection may use the session URL)",
            )
        }
        sessionPath = request.path
        val incoming = parsed["streams"] as? List<*>
        val reply = linkedMapOf<String, Any>()
        if (incoming == null || incoming.isEmpty()) {
            // UxPlay answers this plist with timingPort then eventPort 0 and nothing else.
            // eventPort was the HTTP port in v12 (body 77). iOS 26 then tore the session
            // down after RECORD and never sent the type 110 SETUP. Inventing that stream
            // here is what v11 did; iOS tore that down immediately.
            reply["timingPort"] = ports.timingPort
            reply["eventPort"] = 0
            if (clientTimingPort > 0) mirror?.noteTiming(request.remoteIp, clientTimingPort)
            DmrLog.i(
                "airplay SETUP session only; reply timingPort=${ports.timingPort} eventPort=0 " +
                    "path=${request.path} combined=${parsed["combinedGetInfoWithControlSetup"]} " +
                    "mirroring=${parsed["isScreenMirroringSession"]} " +
                    "timingProtocol=${parsed["timingProtocol"]} et=${parsed["et"]}",
            )
        } else {
            if (ekey != null || parsed.containsKey("timingPort") || parsed.containsKey("eiv")) {
                reply["timingPort"] = ports.timingPort
                reply["eventPort"] = 0
                if (clientTimingPort > 0) mirror?.noteTiming(request.remoteIp, clientTimingPort)
            }
            val secret = pairing.ecdhSecret(request.remoteIp)
            val streams = streamReplies(incoming) { type, id, ct ->
                val rawKey = sessionKey
                if (rawKey == null) {
                    if (type == 110L) mirror?.fail("镜像密钥解开失败")
                    return@streamReplies
                }
                when (type) {
                    110L -> mirror?.armVideo(rawKey, secret, id, request.remoteIp, clientTimingPort)
                    96L -> mirror?.armAudio(rawKey, secret, sessionIv, ct)
                }
            }
            reply["streams"] = streams
            DmrLog.i("airplay SETUP streams reply types=${streamTypes(streams)}")
        }
        val body = BinaryPlist.write(reply)
        DmrLog.i("airplay SETUP reply bytes=${body.size} plist=${describePlist(reply)}")
        return HttpResponse(
            200,
            "OK",
            linkedMapOf("Content-Type" to "application/x-apple-binary-plist"),
            body,
        )
    }

    private fun unwrapKey(ekey: ByteArray?, message: ByteArray?): ByteArray? {
        if (ekey == null) return null
        if (ekey.size == 16) {
            DmrLog.i("airplay ekey is already 16 bytes")
            return ekey.copyOf()
        }
        if (ekey.size < 72 || message == null || message.size < 164) {
            DmrLog.i("airplay ekey=${ekey.size} keymsg=${message?.size ?: 0} cannot call playfair_decrypt")
            return null
        }
        val unwrapped = PlayfairNative.decrypt(message, ekey)
        DmrLog.i("airplay playfair_decrypt ekey=${ekey.size} result=${unwrapped?.size ?: 0}")
        return unwrapped
    }

    private fun streamReplies(value: Any?, onStream: (Long, Long, Int) -> Unit): List<Map<String, Any>> {
        val incoming = (value as? List<*>) ?: emptyList<Any?>()
        val replies = ArrayList<Map<String, Any>>()
        for (item in incoming) {
            val stream = item as? Map<*, *> ?: continue
            val type = number(stream["type"]) ?: continue
            val id = number(stream["streamConnectionID"]) ?: 0L
            val ct = number(stream["ct"])?.toInt() ?: 0
            val dataPort = if (type == 96L && ports.audioPort > 0) ports.audioPort else ports.dataPort
            val reply = linkedMapOf<String, Any>("dataPort" to dataPort)
            if (type == 96L && ports.audioControlPort > 0) reply["controlPort"] = ports.audioControlPort
            reply["type"] = type
            val kind = when (type) {
                110L -> "mirror"
                96L -> "audio"
                else -> "other"
            }
            DmrLog.i("airplay SETUP stream type=$type ($kind) id=$id ct=$ct data=$dataPort")
            onStream(type, id, ct)
            replies += reply
        }
        return replies
    }

    private fun streamTypes(streams: List<Map<String, Any>>): String =
        streams.joinToString(",") { it["type"].toString() }

    private fun parameter(request: HttpRequest, read: Boolean): HttpResponse {
        val text = String(request.body, Charsets.UTF_8).replace('\r', ' ').take(120)
        DmrLog.i("airplay ${request.method} from ${request.remoteIp} body=$text")
        if (!read) return empty(200, "OK")
        if (String(request.body, Charsets.UTF_8).contains("volume")) {
            return text(200, "OK", "volume: 0.000000\r\n", "text/parameters")
        }
        return empty(200, "OK")
    }

    private fun record(request: HttpRequest): HttpResponse {
        DmrLog.i("airplay record from ${request.remoteIp} bytes=${request.body.size}")
        return HttpResponse(
            200,
            "OK",
            linkedMapOf(
                "Audio-Latency" to "11025",
                "Audio-Jack-Status" to "connected; type=analog",
            ),
        )
    }

    private fun teardown(request: HttpRequest): HttpResponse {
        val parsed = plistDictionary(request.body)
        val types = streamTypeList(parsed?.get("streams"))
        val hex = if (request.body.size <= 80) {
            AirPlayKeys.hex(request.body)
        } else {
            AirPlayKeys.hexPrefix(request.body, 32)
        }
        if (request.body.size == 42 && (parsed == null || parsed.isEmpty())) {
            DmrLog.i("airplay teardown 42-byte body is an empty plist dict; full session teardown")
        }
        DmrLog.i(
            "airplay teardown id=${request.connectionId} from ${request.remoteIp} " +
                "bytes=${request.body.size} types=${types.joinToString(",")} " +
                "plist=${describePlist(parsed)} hex=$hex",
        )
        if (MirrorTeardown.stopsVideo(types)) {
            DmrLog.i("airplay teardown closes the mirror id=${request.connectionId}")
            mirror?.stop("teardown id=${request.connectionId}")
        } else {
            DmrLog.i("airplay teardown type 96 only; mirror stays open id=${request.connectionId}")
        }
        return HttpResponse(
            200,
            "OK",
            linkedMapOf("Connection" to "close", "Content-Length" to "0"),
        )
    }

    private fun streamTypeList(value: Any?): List<Long> {
        val incoming = value as? List<*> ?: return emptyList()
        val types = ArrayList<Long>()
        for (item in incoming) {
            val stream = item as? Map<*, *> ?: continue
            val type = number(stream["type"]) ?: continue
            types += type
        }
        return types
    }

    private fun describePlist(value: Any?, depth: Int = 0): String {
        if (value == null) return "null"
        if (depth > 4) return "…"
        return when (value) {
            is ByteArray -> "bytes=${value.size}:${AirPlayKeys.hexPrefix(value, 8)}"
            is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
                "$key=${describePlist(item, depth + 1)}"
            }
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { describePlist(it, depth + 1) }
            is Boolean, is Number, is String -> value.toString()
            else -> value.javaClass.simpleName
        }
    }

    private fun rtspSimple(request: HttpRequest, name: String): HttpResponse {
        DmrLog.i(
            "airplay $name from ${request.remoteIp} bytes=${request.body.size} " +
                "hex=${AirPlayKeys.hexPrefix(request.body, 16)}",
        )
        return empty(200, "OK")
    }

    private fun plistDictionary(body: ByteArray): Map<*, *>? {
        if (body.size < 8 || !body.copyOfRange(0, 8).contentEquals("bplist00".toByteArray(Charsets.US_ASCII))) {
            return null
        }
        return try {
            BinaryPlist.top(body) as? Map<*, *>
        } catch (error: Throwable) {
            DmrLog.i("airplay SETUP plist failed: ${error.message}")
            null
        }
    }

    private fun number(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        else -> null
    }

    private fun options(): HttpResponse = HttpResponse(
        200,
        "OK",
        linkedMapOf(
            "Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET",
        ),
    )

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
                headers["Audio-Jack-Status"] = "connected; type=digital"
            }
            // UxPlay's mirror SETUP, GET /info and RECORD replies have no Session,
            // Date or Transport header. Video /play still carries Session.
            if (!mirrorHandshake(request) && headers.keys.none { it.equals("Session", true) }) {
                headers["Session"] = "CAFEBABE"
            }
            if (!mirrorHandshake(request) && headers.keys.none { it.equals("Date", true) }) {
                headers["Date"] = httpDate()
            }
        }
        return HttpResponse(response.status, response.reason, headers, response.body)
    }

    private fun mirrorHandshake(request: HttpRequest): Boolean {
        if (request.method == "SETUP" || request.method == "RECORD") return true
        return request.method == "GET" && request.path == "/info"
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

    /** SETUP/RECORD/feedback mark the long-lived control socket. HTTP /feedback only refreshes the clock. */
    private fun touchMirror(request: HttpRequest) {
        if (request.path == "/feedback") mirror?.noteFeedback()
        if (!request.protocol.startsWith("RTSP", ignoreCase = true)) return
        val sessionMethod = request.method == "SETUP" ||
            request.method == "RECORD" ||
            request.method == "TEARDOWN" ||
            request.method == "GET_PARAMETER" ||
            request.method == "SET_PARAMETER" ||
            request.method == "FLUSH" ||
            request.path == "/feedback"
        if (!sessionMethod) return
        mirror?.noteControl(request.connectionId, request.remoteIp)
    }

    private fun empty(status: Int, reason: String): HttpResponse =
        HttpResponse(status, reason, linkedMapOf("Content-Length" to "0"))

    private fun octets(body: ByteArray): HttpResponse = HttpResponse(
        200,
        "OK",
        linkedMapOf("Content-Type" to "application/octet-stream"),
        body,
    )

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
            "x-apple-hkp",
        )
    }
}

/** Type 110 or a teardown with no stream list closes the mirror. Type 96 does not. */
internal object MirrorTeardown {
    fun stopsVideo(types: List<Long>): Boolean {
        if (110L in types) return true
        if (types.isEmpty()) return true
        return types.any { it != 96L }
    }
}
