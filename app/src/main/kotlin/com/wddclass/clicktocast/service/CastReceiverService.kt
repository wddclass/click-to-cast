package com.wddclass.clicktocast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import com.wddclass.clicktocast.BuildConfig
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.DeviceProfile
import com.wddclass.clicktocast.dlna.DlnaRenderer
import com.wddclass.clicktocast.dlna.DmrLog
import com.wddclass.clicktocast.dlna.GenaPublisher
import com.wddclass.clicktocast.dlna.MiniHttpServer
import com.wddclass.clicktocast.dlna.RendererHttpHandler
import com.wddclass.clicktocast.dlna.SessionSnapshot
import com.wddclass.clicktocast.dlna.SocketEventTransport
import com.wddclass.clicktocast.dlna.SsdpAdvertiser
import com.wddclass.clicktocast.dlna.Upnp
import com.wddclass.clicktocast.net.NetworkAddresses
import com.wddclass.clicktocast.player.ExoPlayerBackend
import com.wddclass.clicktocast.prefs.CastHistory
import com.wddclass.clicktocast.prefs.CastRecord
import com.wddclass.clicktocast.ui.HomeActivity
import com.wddclass.clicktocast.ui.PlayerActivity
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt

enum class StatusTone { READY, ACTIVE, ERROR }

data class UiState(
    val deviceName: String = Upnp.DEFAULT_FRIENDLY_NAME,
    val hostAddress: String = "",
    val statusLine: String = "等待投屏",
    val statusTone: StatusTone = StatusTone.READY,
    val title: String = "",
    val sourceLine: String = "",
    val showPlayer: Boolean = false,
    val error: String? = null,
    val playing: Boolean = false,
    val volume: Int = 40,
    val session: Long = 0,
    val quality: String = "",
)

class CastReceiverService : Service() {
    interface Api {
        fun snapshot(): UiState
        fun observe(listener: (UiState) -> Unit): () -> Unit
        fun player(): ExoPlayer?
        fun applySettings(name: String, autoStart: Boolean, browserUserAgent: Boolean)
        fun stopPlayback()
        fun togglePlay()
        fun seekBy(deltaMs: Long)
        fun adjustVolume(deltaPercent: Int)
        fun history(): List<CastRecord>
        fun clearHistory()
        fun shutdown()
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(UiState) -> Unit>()
    private lateinit var settings: com.wddclass.clicktocast.prefs.ReceiverSettings
    private lateinit var historyStore: CastHistory
    private lateinit var playerBackend: ExoPlayerBackend
    private lateinit var events: GenaPublisher
    private lateinit var renderer: DlnaRenderer
    private lateinit var http: MiniHttpServer
    private lateinit var ssdp: SsdpAdvertiser
    private var audio: AudioManager? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var httpPort = Upnp.PREFERRED_PORT
    private var configId = 1
    private var hostAddress = ""
    private var latest = UiState()
    private var playerOnTop = false
    private var recordedSession = 0L

    private val ticker = object : Runnable {
        override fun run() {
            refreshAddress()
            main.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        DmrLog.out = { message -> android.util.Log.i(TAG, message) }
        settings = com.wddclass.clicktocast.prefs.ReceiverSettings(this)
        historyStore = CastHistory(this)
        audio = getSystemService(AudioManager::class.java)
        promote(getString(R.string.notif_waiting))
        acquireMulticast()
        playerBackend = ExoPlayerBackend(this) { snap ->
            if (::renderer.isInitialized) renderer.onEngineUpdate(snap)
        }
        events = GenaPublisher(
            SocketEventTransport(),
            GenaPublisher.defaultScheduler(),
            properties = { service -> renderer.eventProperties(service) },
        )
        renderer = DlnaRenderer(
            playerBackend,
            { service -> events.publish(service) },
            { settings.userAgent() },
        ) { snap -> main.post { onSession(snap) } }
        renderer.volumeApplier = { percent, muted -> main.post { applyVolume(percent, muted) } }
        val (percent, muted) = readVolume()
        renderer.adoptVolume(percent, muted, echo = false)
        http = MiniHttpServer { request ->
            RendererHttpHandler(renderer, { currentProfile() }, events).handle(request)
        }
        httpPort = http.start(Upnp.PREFERRED_PORT)
        ssdp = SsdpAdvertiser(profileProvider = { currentProfile() }, configId = { configId })
        ssdp.start()
        refreshAddress()
        main.post(ticker)
        DmrLog.i("receiver ready as ${settings.deviceName} on port $httpPort")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = LocalApi()

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (::ssdp.isInitialized) ssdp.stop()
        if (::http.isInitialized) http.stop()
        if (::playerBackend.isInitialized) playerBackend.release()
        try {
            multicast?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun currentProfile(): DeviceProfile = DeviceProfile(
        friendlyName = settings.deviceName.ifBlank { Upnp.DEFAULT_FRIENDLY_NAME },
        uuid = settings.deviceUuid,
        httpPort = httpPort,
        hostAddress = hostAddress,
        version = BuildConfig.VERSION_NAME,
    )

    private fun refreshAddress() {
        val next = NetworkAddresses.bestIpv4().orEmpty()
        if (next == hostAddress) return
        hostAddress = next
        if (::ssdp.isInitialized) ssdp.announceSoon()
        if (::renderer.isInitialized) onSession(renderer.snapshot())
    }

    private fun onSession(snap: SessionSnapshot) {
        val state = toUi(snap)
        latest = state
        val text = if (state.playing && state.title.isNotBlank()) {
            getString(R.string.notif_playing, state.title)
        } else {
            getString(R.string.notif_waiting)
        }
        promote(text)
        listeners.forEach { listener -> listener(state) }
        record(snap)
        if (state.showPlayer && !playerOnTop) {
            playerOnTop = true
            startActivity(
                Intent(this, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        if (!state.showPlayer) playerOnTop = false
    }

    private fun toUi(snap: SessionSnapshot): UiState {
        val tone = when {
            snap.error != null || snap.statusLine == "播放失败" -> StatusTone.ERROR
            snap.playing || snap.statusLine == "已暂停" || snap.statusLine == "播放结束" -> StatusTone.ACTIVE
            else -> StatusTone.READY
        }
        return UiState(
            deviceName = settings.deviceName.ifBlank { Upnp.DEFAULT_FRIENDLY_NAME },
            hostAddress = hostAddress,
            statusLine = snap.statusLine,
            statusTone = tone,
            title = snap.title,
            sourceLine = snap.sourceLine,
            showPlayer = snap.showPlayer,
            error = snap.error,
            playing = snap.playing,
            volume = snap.volume,
            session = snap.session,
            quality = snap.quality,
        )
    }

    private fun record(snap: SessionSnapshot) {
        if (snap.session == 0L || snap.title.isBlank()) return
        val outcome = when (snap.statusLine) {
            "正在播放", "正在缓冲", "已暂停" -> "正在播放"
            "播放失败" -> "播放失败"
            "播放结束" -> "已结束"
            "已收到片源" -> "已接收"
            else -> "已停止"
        }
        if (snap.session != recordedSession) {
            recordedSession = snap.session
            historyStore.add(
                CastRecord(
                    at = System.currentTimeMillis(),
                    title = snap.title,
                    source = snap.sourceLine,
                    quality = snap.quality,
                    outcome = outcome,
                    session = snap.session,
                ),
            )
        } else {
            historyStore.updateSession(snap.session, outcome, snap.quality)
        }
    }

    private fun applyVolume(percent: Int, muted: Boolean) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        val currentlyMuted = manager.isStreamMute(AudioManager.STREAM_MUSIC)
        if (muted && !currentlyMuted) {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
        } else if (!muted && currentlyMuted) {
            manager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
    }

    private fun readVolume(): Pair<Int, Boolean> {
        val manager = audio ?: return 40 to false
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val index = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val percent = (index * 100f / max).roundToInt().coerceIn(0, 100)
        return percent to manager.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    private fun acquireMulticast() {
        try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            multicast = wifi.createMulticastLock("clicktocast").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (error: Exception) {
            DmrLog.i("multicast lock unavailable: ${error.message}")
        }
    }

    private fun promote(text: String) {
        ensureChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val playback = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            val combined = if (Build.VERSION.SDK_INT >= 34) {
                playback or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            } else {
                playback
            }
            try {
                startForeground(NOTIF_ID, notification, combined)
            } catch (_: Exception) {
                startForeground(NOTIF_ID, notification, playback)
            }
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.channel_desc)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private inner class LocalApi : Binder(), Api {
        override fun snapshot(): UiState = latest

        override fun observe(listener: (UiState) -> Unit): () -> Unit {
            listeners += listener
            listener(latest)
            return { listeners -= listener }
        }

        override fun player(): ExoPlayer? = if (::playerBackend.isInitialized) playerBackend.player else null

        override fun applySettings(name: String, autoStart: Boolean, browserUserAgent: Boolean) {
            settings.deviceName = name.trim().ifBlank { Upnp.DEFAULT_FRIENDLY_NAME }
            settings.autoStart = autoStart
            settings.browserUserAgent = browserUserAgent
            configId += 1
            if (::ssdp.isInitialized) ssdp.announceSoon()
            if (::renderer.isInitialized) onSession(renderer.snapshot())
        }

        override fun stopPlayback() {
            if (::renderer.isInitialized) {
                renderer.handleAv("Stop", mapOf("InstanceID" to "0"), null)
            }
        }

        override fun togglePlay() {
            if (!::renderer.isInitialized) return
            val state = renderer.snapshot().transportState
            if (state == "PLAYING" || state == "TRANSITIONING") {
                renderer.handleAv("Pause", mapOf("InstanceID" to "0"), null)
            } else {
                renderer.handleAv("Play", mapOf("InstanceID" to "0", "Speed" to "1"), null)
            }
        }

        override fun seekBy(deltaMs: Long) {
            if (!::playerBackend.isInitialized) return
            val next = playerBackend.snapshot().positionMs + deltaMs
            playerBackend.seekTo(next)
        }

        override fun adjustVolume(deltaPercent: Int) {
            if (!::renderer.isInitialized) return
            val current = renderer.snapshot()
            renderer.adoptVolume((current.volume + deltaPercent).coerceIn(0, 100), current.muted, echo = true)
        }

        override fun history(): List<CastRecord> = historyStore.list()

        override fun clearHistory() = historyStore.clear()

        override fun shutdown() {
            if (::ssdp.isInitialized) ssdp.stop()
            if (::http.isInitialized) http.stop()
            stopForeground(true)
            stopSelf()
        }
    }

    companion object {
        private const val TAG = "ClickToCast"
        private const val CHANNEL = "cast_receiver"
        private const val NOTIF_ID = 7
    }
}
