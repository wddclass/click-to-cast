package com.wddclass.clicktocast.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.wddclass.clicktocast.dlna.EnginePlayback
import com.wddclass.clicktocast.dlna.MediaKind
import com.wddclass.clicktocast.dlna.PlaybackRequest
import com.wddclass.clicktocast.dlna.PlaybackSnapshot
import com.wddclass.clicktocast.dlna.PlayerBackend
import com.wddclass.clicktocast.dlna.StreamSniffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ExoPlayerBackend(
    context: Context,
    private val listener: (PlaybackSnapshot) -> Unit,
) : PlayerBackend {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "dmr-sniff").apply { isDaemon = true }
    }
    private val generation = AtomicInteger()
    private val playRequested = AtomicBoolean(false)

    @Volatile
    private var cached = PlaybackSnapshot()

    val player: ExoPlayer

    init {
        val trackSelector = DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                .setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setAllowVideoMixedMimeTypeAdaptiveness(true)
                .setAllowVideoNonSeamlessAdaptiveness(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setTunnelingEnabled(false)
                .build()
        }
        val renderers = DefaultRenderersFactory(appContext)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        player = ExoPlayer.Builder(appContext)
            .setRenderersFactory(renderers)
            .setTrackSelector(trackSelector)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true,
        )
        player.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        player.setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = publish()
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = publish()
            override fun onPlayerError(error: PlaybackException) = publish()
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) = publish()
        })
        main.post(object : Runnable {
            override fun run() {
                publish()
                main.postDelayed(this, 500)
            }
        })
    }

    override fun load(request: PlaybackRequest) {
        val token = generation.incrementAndGet()
        playRequested.set(false)
        worker.execute {
            val kind = StreamSniffer.sniff(request.uri, request.userAgent, request.headers)
            main.post {
                if (token != generation.get()) return@post
                open(request, kind)
            }
        }
    }

    override fun play() {
        playRequested.set(true)
        main.post {
            player.playWhenReady = true
            publish()
        }
    }

    override fun pause() {
        playRequested.set(false)
        main.post {
            player.pause()
            publish()
        }
    }

    override fun stop() {
        generation.incrementAndGet()
        playRequested.set(false)
        main.post {
            player.stop()
            player.clearMediaItems()
            publish()
        }
    }

    override fun seekTo(positionMs: Long) {
        main.post {
            val duration = player.duration
            val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
            player.seekTo(target)
            publish()
        }
    }

    override fun snapshot(): PlaybackSnapshot = cached

    fun release() {
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        main.post {
            player.release()
        }
    }

    private fun open(request: PlaybackRequest, kind: MediaKind) {
        try {
            val http = DefaultHttpDataSource.Factory()
                .setUserAgent(request.userAgent)
                .setDefaultRequestProperties(request.headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(20_000)
            val item = MediaItem.Builder()
                .setUri(request.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
                .apply {
                    mimeFor(kind, request.mimeHint)?.let { setMimeType(it) }
                }
                .build()
            player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
            player.prepare()
            if (request.startPositionMs > 0) player.seekTo(request.startPositionMs)
            player.playWhenReady = playRequested.get()
            publish()
        } catch (error: Exception) {
            cached = PlaybackSnapshot(EnginePlayback.ERROR, 0, -1, "视频无法开始播放")
            listener(cached)
        }
    }

    private fun publish() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { publish() }
            return
        }
        val error = player.playerError
        val playback = when {
            error != null -> EnginePlayback.ERROR
            player.playbackState == Player.STATE_BUFFERING -> EnginePlayback.BUFFERING
            player.playbackState == Player.STATE_ENDED -> EnginePlayback.ENDED
            player.playbackState == Player.STATE_READY && player.playWhenReady -> EnginePlayback.READY_PLAY
            player.playbackState == Player.STATE_READY -> EnginePlayback.READY_PAUSE
            else -> EnginePlayback.IDLE
        }
        val duration = player.duration
        val next = PlaybackSnapshot(
            playback = playback,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration == C.TIME_UNSET || duration < 0) -1L else duration,
            error = error?.let { friendly(it) },
            quality = qualityOf(),
        )
        val previous = cached
        cached = next
        if (previous.playback != next.playback || previous.error != next.error || previous.quality != next.quality) {
            listener(next)
        }
    }

    private fun qualityOf(): String {
        val format = player.videoFormat ?: return ""
        val size = when {
            format.width >= 3000 -> "4K"
            format.width >= 1900 -> "1080p"
            format.width >= 1200 -> "720p"
            format.width > 0 -> "${format.width}p"
            else -> ""
        }
        val transfer = format.colorInfo?.colorTransfer
        val hdr = transfer == C.COLOR_TRANSFER_ST2084 || transfer == C.COLOR_TRANSFER_HLG
        return listOf(size, if (hdr) "HDR" else "").filter { it.isNotBlank() }.joinToString(" ")
    }

    private fun mimeFor(kind: MediaKind, declared: String?): String? = when (kind) {
        MediaKind.HLS -> MimeTypes.APPLICATION_M3U8
        MediaKind.DASH -> MimeTypes.APPLICATION_MPD
        MediaKind.FLV -> MimeTypes.VIDEO_FLV
        MediaKind.PROGRESSIVE -> declared?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
        MediaKind.UNKNOWN -> null
    }

    private fun friendly(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "网络连接失败"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        -> "视频地址无法打开"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "这部电视解不了这个视频"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> "视频格式无法识别"
        else -> "播放失败"
    }
}
