package com.wddclass.clicktocast.dlna

data class SessionSnapshot(
    val transportState: String,
    val transportStatus: String,
    val statusLine: String,
    val title: String,
    val sourceLine: String,
    val showPlayer: Boolean,
    val error: String?,
    val volume: Int,
    val muted: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val quality: String,
    val playing: Boolean,
    val session: Long,
)

/**
 * UPnP AVTransport, RenderingControl and ConnectionManager for a single renderer instance.
 * Player calls happen outside the state lock so a synchronous player callback cannot deadlock.
 */
class DlnaRenderer(
    private val player: PlayerBackend,
    private val publish: (service: String) -> Unit,
    private val userAgent: () -> String,
    private val onSession: (SessionSnapshot) -> Unit = {},
) {
    private val lock = Any()
    private var uri = ""
    private var metadata = ""
    private var title = ""
    private var sourceLine = "来自 局域网设备"
    private var mimeHint: String? = null
    private var headers: Map<String, String> = emptyMap()
    private var activeUserAgent = ""
    private var nextUri = ""
    private var nextMetadata = ""
    private var desired = DesiredTransport.STOPPED
    private var transportStatus = "OK"
    private var errorText: String? = null
    private var naturalEnd = false
    private var volume = 40
    private var muted = false
    private var speed = "1"
    private var lastQuality = ""
    private var session = 0L
    private var pendingFraction = 0.0

    var volumeApplier: ((percent: Int, muted: Boolean) -> Unit)? = null

    fun handleAv(action: String, args: Map<String, String>, requestUserAgent: String?): SoapResult {
        if (!instanceOk(args)) return SoapResult.fault(718, "Invalid InstanceID")
        val name = SoapCodec.canonical(action)
        val result = when (name) {
            "SetAVTransportURI" -> setUri(
                SoapCodec.arg(args, "CurrentURI").orEmpty(),
                SoapCodec.arg(args, "CurrentURIMetaData").orEmpty(),
                requestUserAgent,
            )
            "SetNextAVTransportURI" -> setNext(
                SoapCodec.arg(args, "NextURI").orEmpty(),
                SoapCodec.arg(args, "NextURIMetaData").orEmpty(),
            )
            "Play" -> play(SoapCodec.arg(args, "Speed"))
            "Pause" -> pause()
            "Stop" -> stop()
            "Seek" -> seek(SoapCodec.arg(args, "Unit").orEmpty(), SoapCodec.arg(args, "Target").orEmpty())
            "GetPositionInfo" -> positionInfo()
            "GetTransportInfo" -> transportInfo()
            "GetMediaInfo" -> mediaInfo()
            "GetDeviceCapabilities" -> SoapResult.ok(
                Upnp.AVT,
                name,
                linkedMapOf(
                    "PlayMedia" to "NONE,NETWORK",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED",
                ),
            )
            "GetTransportSettings" -> SoapResult.ok(
                Upnp.AVT,
                name,
                linkedMapOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED"),
            )
            "GetCurrentTransportActions" -> SoapResult.ok(
                Upnp.AVT,
                name,
                linkedMapOf("Actions" to actions()),
            )
            else -> SoapResult.fault(401, "Invalid Action")
        }
        return result
    }

    fun handleRc(action: String, args: Map<String, String>): SoapResult {
        if (!instanceOk(args)) return SoapResult.fault(718, "Invalid InstanceID")
        val name = SoapCodec.canonical(action)
        return when (name) {
            "GetVolume" -> SoapResult.ok(Upnp.RCS, name, linkedMapOf("CurrentVolume" to volume.toString()))
            "SetVolume" -> setVolume(SoapCodec.arg(args, "DesiredVolume"))
            "GetMute" -> SoapResult.ok(Upnp.RCS, name, linkedMapOf("CurrentMute" to if (muted) "1" else "0"))
            "SetMute" -> setMute(SoapCodec.arg(args, "DesiredMute"))
            "ListPresets" -> SoapResult.ok(Upnp.RCS, name, linkedMapOf("CurrentPresetNameList" to "FactoryDefaults"))
            "SelectPreset" -> {
                adoptVolume(40, false, echo = true)
                SoapResult.ok(Upnp.RCS, name)
            }
            else -> SoapResult.fault(401, "Invalid Action")
        }
    }

    fun handleCm(action: String, args: Map<String, String>): SoapResult {
        val name = SoapCodec.canonical(action)
        return when (name) {
        "GetProtocolInfo" -> SoapResult.ok(
            Upnp.CMS,
            name,
            linkedMapOf("Source" to "", "Sink" to Upnp.SINK_PROTOCOLS),
        )
        "GetCurrentConnectionIDs" -> SoapResult.ok(Upnp.CMS, name, linkedMapOf("ConnectionIDs" to "0"))
        "GetCurrentConnectionInfo" -> {
            val id = SoapCodec.arg(args, "ConnectionID") ?: "0"
            if (id != "0") return SoapResult.fault(706, "Invalid connection reference")
            SoapResult.ok(
                Upnp.CMS,
                name,
                linkedMapOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK",
                ),
            )
        }
        else -> SoapResult.fault(401, "Invalid Action")
        }
    }

    fun onEngineUpdate(snap: PlaybackSnapshot) {
        var stateChanged = false
        var qualityChanged = false
        synchronized(lock) {
            when (snap.playback) {
                EnginePlayback.ERROR -> {
                    if (transportStatus != "ERROR_OCCURRED" || errorText != snap.error) stateChanged = true
                    transportStatus = "ERROR_OCCURRED"
                    errorText = snap.error ?: "播放失败"
                    desired = DesiredTransport.STOPPED
                    naturalEnd = false
                }
                EnginePlayback.ENDED -> {
                    if (desired == DesiredTransport.PLAYING && !naturalEnd) stateChanged = true
                    if (desired == DesiredTransport.PLAYING) {
                        desired = DesiredTransport.STOPPED
                        naturalEnd = true
                        transportStatus = "OK"
                        errorText = null
                    }
                }
                else -> Unit
            }
            if (snap.quality.isNotBlank() && snap.quality != lastQuality) {
                lastQuality = snap.quality
                qualityChanged = true
            }
        }
        val pendingSeek = synchronized(lock) {
            if (pendingFraction > 0.0 && snap.durationMs > 0) {
                val target = (snap.durationMs * pendingFraction).toLong()
                pendingFraction = 0.0
                target
            } else {
                null
            }
        }
        if (pendingSeek != null) player.seekTo(pendingSeek)
        if (stateChanged) publish(Upnp.AVT)
        if (stateChanged || qualityChanged) emit()
    }

    fun replaySource(): Triple<String, String, Map<String, String>> = synchronized(lock) {
        Triple(uri, activeUserAgent, headers)
    }

    /** Play a URL kept in local history. Headers are only those saved with the entry. */
    fun replayMedia(url: String, title: String, agent: String, extra: Map<String, String>): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) return false
        val resolvedTitle = title.trim().ifBlank { CastMetadata.safeTitle("", trimmed) }
        val resolvedAgent = agent.ifBlank { userAgent() }
        val request = PlaybackRequest(
            uri = trimmed,
            title = resolvedTitle,
            mimeHint = null,
            userAgent = resolvedAgent,
            headers = extra,
            startPositionMs = 0L,
        )
        synchronized(lock) {
            uri = trimmed
            metadata = ""
            this.title = resolvedTitle
            sourceLine = "来自 投屏记录"
            mimeHint = null
            headers = extra
            activeUserAgent = resolvedAgent
            session += 1
            desired = DesiredTransport.STOPPED
            naturalEnd = false
            transportStatus = "OK"
            errorText = null
            speed = "1"
            pendingFraction = 0.0
        }
        DmrLog.i("replay ${redactUrl(trimmed)} title=$resolvedTitle")
        player.load(request)
        publish(Upnp.AVT)
        emit()
        return play("1").httpStatus == 200
    }

    /** AirPlay POST /play. Loads the URL and starts playback. [startFraction] is 0..1. */
    fun castUrl(url: String, userAgent: String, startFraction: Double): Boolean {
        if (setUri(url, "", userAgent).httpStatus != 200) return false
        synchronized(lock) {
            val fraction = startFraction.coerceIn(0.0, 1.0)
            pendingFraction = if (fraction > 0.001) fraction else 0.0
        }
        return play("1").httpStatus == 200
    }

    fun pausePlayback() {
        pause()
    }

    fun resumePlayback() {
        play("1")
    }

    fun stopPlayback() {
        stop()
    }

    fun seekSeconds(seconds: Double) {
        val hasMedia = synchronized(lock) { uri.isNotBlank() }
        if (!hasMedia) return
        player.seekTo((seconds * 1000.0).toLong().coerceAtLeast(0))
    }

    fun adoptVolume(percent: Int, isMuted: Boolean, echo: Boolean) {
        synchronized(lock) {
            volume = percent.coerceIn(0, 100)
            muted = isMuted
        }
        if (echo) volumeApplier?.invoke(volume, muted)
        publish(Upnp.RCS)
        emit()
    }

    fun snapshot(): SessionSnapshot {
        val engine = player.snapshot()
        return synchronized(lock) { buildSnapshot(engine) }
    }

    fun eventProperties(service: String): Map<String, String> = synchronized(lock) {
        when (service) {
            Upnp.AVT -> linkedMapOf("LastChange" to avLastChange(player.snapshot()))
            Upnp.RCS -> linkedMapOf("LastChange" to rcLastChange())
            Upnp.CMS -> linkedMapOf(
                "SourceProtocolInfo" to "",
                "SinkProtocolInfo" to Upnp.SINK_PROTOCOLS,
                "CurrentConnectionIDs" to "0",
            )
            else -> emptyMap()
        }
    }

    private fun setUri(rawUri: String, rawMetadata: String, requestUserAgent: String?): SoapResult {
        val trimmed = SoapCodec.unescapeXml(rawUri).trim()
        if (trimmed.isEmpty()) return SoapResult.fault(402, "Invalid Args")
        val parsed = try {
            CastMetadata.parse(rawMetadata)
        } catch (error: Throwable) {
            DmrLog.i("metadata ignored: ${error.javaClass.simpleName}")
            ParsedMetadata()
        }
        val resolvedTitle = CastMetadata.safeTitle(parsed.title, trimmed)
        val resolvedAgent = parsed.userAgent ?: userAgent()
        val source = SenderLabel.sourceLine(requestUserAgent ?: parsed.userAgent, rawMetadata, trimmed)
        val request = PlaybackRequest(
            uri = trimmed,
            title = resolvedTitle,
            mimeHint = parsed.mimeType,
            userAgent = resolvedAgent,
            headers = parsed.headers,
            startPositionMs = 0L,
        )
        synchronized(lock) {
            uri = trimmed
            metadata = rawMetadata
            title = resolvedTitle
            sourceLine = source
            mimeHint = parsed.mimeType
            headers = parsed.headers
            activeUserAgent = resolvedAgent
            session += 1
            desired = DesiredTransport.STOPPED
            naturalEnd = false
            transportStatus = "OK"
            errorText = null
            speed = "1"
        }
        DmrLog.i("set uri ${redactUrl(trimmed)} title=$resolvedTitle")
        player.load(request)
        publish(Upnp.AVT)
        emit()
        return SoapResult.ok(Upnp.AVT, "SetAVTransportURI")
    }

    private fun setNext(rawUri: String, rawMetadata: String): SoapResult {
        synchronized(lock) {
            nextUri = rawUri.trim()
            nextMetadata = rawMetadata
        }
        return SoapResult.ok(Upnp.AVT, "SetNextAVTransportURI")
    }

    private fun play(rawSpeed: String?): SoapResult {
        val requested = rawSpeed?.trim().orEmpty().ifBlank { "1" }
        if (requested != "1" && requested != "1.0") return SoapResult.fault(717, "Play speed not supported")
        val hasMedia = synchronized(lock) { uri.isNotBlank() }
        if (!hasMedia) return SoapResult.fault(701, "Transition not available")
        synchronized(lock) {
            desired = DesiredTransport.PLAYING
            naturalEnd = false
            transportStatus = "OK"
            errorText = null
            speed = "1"
        }
        player.play()
        publish(Upnp.AVT)
        emit()
        return SoapResult.ok(Upnp.AVT, "Play")
    }

    private fun pause(): SoapResult {
        val hasMedia = synchronized(lock) { uri.isNotBlank() }
        if (!hasMedia) return SoapResult.fault(701, "Transition not available")
        synchronized(lock) {
            desired = DesiredTransport.PAUSED
            naturalEnd = false
            transportStatus = "OK"
        }
        player.pause()
        publish(Upnp.AVT)
        emit()
        return SoapResult.ok(Upnp.AVT, "Pause")
    }

    private fun stop(): SoapResult {
        synchronized(lock) {
            desired = DesiredTransport.STOPPED
            naturalEnd = false
            transportStatus = "OK"
            speed = "1"
        }
        player.stop()
        publish(Upnp.AVT)
        emit()
        return SoapResult.ok(Upnp.AVT, "Stop")
    }

    private fun seek(unit: String, target: String): SoapResult {
        val hasMedia = synchronized(lock) { uri.isNotBlank() }
        if (!hasMedia) return SoapResult.fault(701, "Transition not available")
        val normalized = unit.trim().uppercase()
        if (normalized != "REL_TIME" && normalized != "ABS_TIME") {
            return SoapResult.fault(710, "Seek mode not supported")
        }
        val current = player.snapshot().positionMs
        val position = DlnaTime.parse(target, current) ?: return SoapResult.fault(402, "Invalid Args")
        player.seekTo(position)
        publish(Upnp.AVT)
        return SoapResult.ok(Upnp.AVT, "Seek")
    }

    private fun positionInfo(): SoapResult {
        val engine = player.snapshot()
        val (track, duration, meta, trackUri, rel) = synchronized(lock) {
            val has = uri.isNotBlank()
            Info(
                if (has) "1" else "0",
                DlnaTime.format(engine.durationMs),
                if (metadata.length <= 8_000) metadata else "",
                uri,
                DlnaTime.format(engine.positionMs),
            )
        }
        return SoapResult.ok(
            Upnp.AVT,
            "GetPositionInfo",
            linkedMapOf(
                "Track" to track,
                "TrackDuration" to duration,
                "TrackMetaData" to meta,
                "TrackURI" to trackUri,
                "RelTime" to rel,
                "AbsTime" to rel,
                "RelCount" to "NOT_IMPLEMENTED",
                "AbsCount" to "NOT_IMPLEMENTED",
            ),
        )
    }

    private fun transportInfo(): SoapResult {
        val state = synchronized(lock) { transportState(player.snapshot()) }
        val status = synchronized(lock) { transportStatus }
        val currentSpeed = synchronized(lock) { speed }
        return SoapResult.ok(
            Upnp.AVT,
            "GetTransportInfo",
            linkedMapOf(
                "CurrentTransportState" to state,
                "CurrentTransportStatus" to status,
                "CurrentSpeed" to currentSpeed,
            ),
        )
    }

    private fun mediaInfo(): SoapResult {
        val engine = player.snapshot()
        val fields = synchronized(lock) {
            linkedMapOf(
                "NrTracks" to if (uri.isBlank()) "0" else "1",
                "MediaDuration" to DlnaTime.format(engine.durationMs),
                "CurrentURI" to uri,
                "CurrentURIMetaData" to metadata,
                "NextURI" to nextUri,
                "NextURIMetaData" to nextMetadata,
                "PlayMedium" to if (uri.isBlank()) "NONE" else "NETWORK",
                "RecordMedium" to "NOT_IMPLEMENTED",
                "WriteStatus" to "NOT_IMPLEMENTED",
            )
        }
        return SoapResult.ok(Upnp.AVT, "GetMediaInfo", fields)
    }

    private fun setVolume(raw: String?): SoapResult {
        val parsed = raw?.trim()?.toIntOrNull() ?: return SoapResult.fault(402, "Invalid Args")
        val nextMute = synchronized(lock) { if (parsed > 0) false else muted }
        adoptVolume(parsed, nextMute, echo = true)
        return SoapResult.ok(Upnp.RCS, "SetVolume")
    }

    private fun setMute(raw: String?): SoapResult {
        val value = raw?.trim()?.lowercase() ?: return SoapResult.fault(402, "Invalid Args")
        val next = when (value) {
            "1", "true", "yes" -> true
            "0", "false", "no" -> false
            else -> return SoapResult.fault(402, "Invalid Args")
        }
        val current = synchronized(lock) { volume }
        adoptVolume(current, next, echo = true)
        return SoapResult.ok(Upnp.RCS, "SetMute")
    }

    private fun instanceOk(args: Map<String, String>): Boolean {
        val id = SoapCodec.arg(args, "InstanceID")?.trim() ?: return true
        return id.isEmpty() || id == "0"
    }

    private fun transportState(engine: PlaybackSnapshot): String = when (desired) {
        DesiredTransport.STOPPED -> if (uri.isBlank()) "NO_MEDIA_PRESENT" else "STOPPED"
        DesiredTransport.PAUSED -> "PAUSED_PLAYBACK"
        DesiredTransport.PLAYING -> when (engine.playback) {
            EnginePlayback.BUFFERING, EnginePlayback.IDLE -> "TRANSITIONING"
            EnginePlayback.ERROR, EnginePlayback.ENDED -> "STOPPED"
            EnginePlayback.READY_PAUSE -> "TRANSITIONING"
            EnginePlayback.READY_PLAY -> "PLAYING"
        }
    }

    private fun actions(): String = when (transportState(player.snapshot())) {
        "PLAYING" -> "Pause,Stop,Seek"
        "PAUSED_PLAYBACK" -> "Play,Stop,Seek"
        "STOPPED" -> if (uri.isBlank()) "" else "Play,Stop,Seek"
        "TRANSITIONING" -> "Stop"
        else -> ""
    }

    private fun statusLine(state: String): String = when {
        errorText != null -> "播放失败"
        naturalEnd -> "播放结束"
        state == "PLAYING" -> "正在播放"
        state == "PAUSED_PLAYBACK" -> "已暂停"
        state == "TRANSITIONING" -> "正在缓冲"
        uri.isNotBlank() -> "已收到片源"
        else -> "等待投屏"
    }

    private fun buildSnapshot(engine: PlaybackSnapshot): SessionSnapshot {
        val state = transportState(engine)
        val show = when {
            uri.isBlank() -> false
            errorText != null -> true
            naturalEnd -> true
            desired == DesiredTransport.PLAYING || desired == DesiredTransport.PAUSED -> true
            else -> false
        }
        return SessionSnapshot(
            transportState = state,
            transportStatus = transportStatus,
            statusLine = statusLine(state),
            title = title,
            sourceLine = sourceLine,
            showPlayer = show,
            error = errorText,
            volume = volume,
            muted = muted,
            positionMs = engine.positionMs,
            durationMs = engine.durationMs,
            quality = engine.quality.ifBlank { lastQuality },
            playing = state == "PLAYING" || state == "TRANSITIONING",
            session = session,
        )
    }

    private fun avLastChange(engine: PlaybackSnapshot): String {
        val state = transportState(engine)
        val duration = DlnaTime.format(engine.durationMs)
        val meta = if (metadata.length <= 1500) metadata else ""
        return buildString {
            append("""<Event xmlns="urn:schemas-upnp-org:metadata-1-0/AVT/"><InstanceID val="0">""")
            append(attr("TransportState", state))
            append(attr("TransportStatus", transportStatus))
            append(attr("CurrentTransportActions", actions()))
            append(attr("AVTransportURI", uri))
            append(attr("AVTransportURIMetaData", meta))
            append(attr("CurrentTrackURI", uri))
            append(attr("CurrentTrackDuration", duration))
            append(attr("CurrentMediaDuration", duration))
            append(attr("NumberOfTracks", if (uri.isBlank()) "0" else "1"))
            append("</InstanceID></Event>")
        }
    }

    private fun rcLastChange(): String =
        """<Event xmlns="urn:schemas-upnp-org:metadata-1-0/RCS/"><InstanceID val="0"><Volume channel="Master" val="$volume"/><Mute channel="Master" val="${if (muted) 1 else 0}"/></InstanceID></Event>"""

    private fun attr(name: String, value: String): String =
        """<$name val="${XmlText.escape(value)}"/>"""

    private fun emit() {
        val snap = snapshot()
        onSession(snap)
    }

    private data class Info(
        val track: String,
        val duration: String,
        val meta: String,
        val trackUri: String,
        val rel: String,
    )
}
