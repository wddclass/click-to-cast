package com.wddclass.clicktocast.dlna

enum class EnginePlayback {
    IDLE,
    BUFFERING,
    READY_PLAY,
    READY_PAUSE,
    ENDED,
    ERROR,
}

data class PlaybackSnapshot(
    val playback: EnginePlayback = EnginePlayback.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = -1L,
    val error: String? = null,
    val quality: String = "",
)

data class PlaybackRequest(
    val uri: String,
    val title: String,
    val mimeHint: String?,
    val userAgent: String,
    val headers: Map<String, String>,
    val startPositionMs: Long,
)

interface PlayerBackend {
    fun load(request: PlaybackRequest)
    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun snapshot(): PlaybackSnapshot
}

data class DeviceProfile(
    val friendlyName: String,
    val uuid: String,
    val httpPort: Int,
    val hostAddress: String,
    val version: String,
) {
    val udn: String get() = "uuid:$uuid"
    val location: String get() = "http://$hostAddress:$httpPort${DmrPaths.DESCRIPTION}"
    val serverHeader: String get() = "Android/11 UPnP/1.0 ${Upnp.PRODUCT}/$version"
    val urlBase: String get() = "http://$hostAddress:$httpPort/"
}

enum class DesiredTransport {
    STOPPED,
    PLAYING,
    PAUSED,
}
