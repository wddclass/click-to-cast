package com.wddclass.clicktocast.player

import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.wddclass.clicktocast.dlna.EnginePlayback
import com.wddclass.clicktocast.dlna.LiveSignals
import com.wddclass.clicktocast.dlna.MediaKind
import com.wddclass.clicktocast.dlna.PlaybackMessages
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

    @Volatile
    private var rate = 1f

    private val bandwidth = DefaultBandwidthMeter.Builder(appContext).build()
    private val throughput = Throughput()
    private val transfer = object : TransferListener {
        override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            bandwidth.onTransferInitializing(source, dataSpec, isNetwork)
        }

        override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            bandwidth.onTransferStart(source, dataSpec, isNetwork)
        }

        override fun onBytesTransferred(
            source: DataSource,
            dataSpec: DataSpec,
            isNetwork: Boolean,
            bytesTransferred: Int,
        ) {
            bandwidth.onBytesTransferred(source, dataSpec, isNetwork, bytesTransferred)
            if (isNetwork && bytesTransferred > 0) throughput.add(bytesTransferred)
        }

        override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
            bandwidth.onTransferEnd(source, dataSpec, isNetwork)
        }
    }

    val player: ExoPlayer

    /** Bytes per second read by the player. Falls back to this app's receive delta while playing. */
    fun downloadBytesPerSecond(): Long = throughput.sample(playerIsActive())

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
            .setBandwidthMeter(bandwidth)
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
                main.postDelayed(this, 250)
            }
        })
    }

    override fun load(request: PlaybackRequest) {
        val token = generation.incrementAndGet()
        playRequested.set(false)
        rate = 1f
        throughput.reset()
        main.post {
            if (token != generation.get()) return@post
            player.stop()
            player.clearMediaItems()
            player.setPlaybackSpeed(rate)
            publish()
        }
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

    override fun setSpeed(speed: Float) {
        if (cached.live) {
            rate = 1f
            main.post {
                player.setPlaybackSpeed(1f)
                publish()
            }
            return
        }
        rate = speed.coerceIn(0.5f, 2f)
        main.post {
            player.setPlaybackSpeed(rate)
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
                .setTransferListener(transfer)
            val item = MediaItem.Builder()
                .setUri(request.uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(request.title).build())
                .apply {
                    mimeFor(kind, request.mimeHint)?.let { setMimeType(it) }
                }
                .build()
            player.stop()
            player.clearMediaItems()
            player.setPlaybackSpeed(rate)
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
        val durationMs = if (duration == C.TIME_UNSET || duration < 0) -1L else duration
        val playlist = (player.currentManifest as? HlsManifest)?.mediaPlaylist
        val hlsLoaded = playlist != null
        val hlsOpen = hlsLoaded && playlist.hasEndTag == false
        val ready = player.playbackState == Player.STATE_READY && player.mediaItemCount > 0
        val next = PlaybackSnapshot(
            playback = playback,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = durationMs,
            error = error?.let { friendly(it) },
            quality = qualityOf(),
            live = LiveSignals.fromEngine(
                playerLive = player.isCurrentMediaItemLive,
                hlsManifestLoaded = hlsLoaded,
                hlsWithoutEndList = hlsOpen,
                durationMs = durationMs,
                ready = ready,
            ),
        )
        val previous = cached
        cached = next
        if (previous.playback != next.playback || previous.error != next.error ||
            previous.quality != next.quality || previous.live != next.live ||
            (previous.durationMs > 0) != (next.durationMs > 0)
        ) {
            listener(next)
        }
    }

    private fun playerIsActive(): Boolean {
        if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return false
        return player.isLoading || player.playWhenReady
    }

    private class Throughput {
        private val lock = Any()
        private var bytes = 0L
        private var windowStart = SystemClock.elapsedRealtime()
        private var uidAtWindow = TrafficStats.getUidRxBytes(Process.myUid())

        @Volatile
        var perSecond = 0L
            private set

        fun add(count: Int) {
            synchronized(lock) { bytes += count.toLong() }
        }

        fun reset() {
            synchronized(lock) {
                bytes = 0L
                windowStart = SystemClock.elapsedRealtime()
                uidAtWindow = TrafficStats.getUidRxBytes(Process.myUid())
                perSecond = 0L
            }
        }

        fun sample(active: Boolean): Long {
            val now = SystemClock.elapsedRealtime()
            synchronized(lock) {
                val elapsed = now - windowStart
                if (elapsed < 1_000L) return perSecond
                val counted = bytes
                bytes = 0L
                val uidThen = uidAtWindow
                val uidNow = TrafficStats.getUidRxBytes(Process.myUid())
                uidAtWindow = uidNow
                windowStart = now
                val uidDelta = if (uidThen >= 0L && uidNow >= 0L) (uidNow - uidThen).coerceAtLeast(0L) else 0L
                val chosen = when {
                    counted > 0L -> counted
                    active && uidDelta > 0L -> uidDelta
                    else -> 0L
                }
                perSecond = chosen * 1_000L / elapsed
                return perSecond
            }
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

    private fun friendly(error: PlaybackException): String =
        PlaybackMessages.describe(error.errorCode, httpStatus(error))

    private fun httpStatus(error: Throwable): Int? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 8) {
            if (current is HttpDataSource.InvalidResponseCodeException) return current.responseCode
            current = current.cause
            depth++
        }
        return null
    }
}
