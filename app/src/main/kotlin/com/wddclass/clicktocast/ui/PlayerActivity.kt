package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.DlnaTime
import com.wddclass.clicktocast.service.CastReceiverService
import com.wddclass.clicktocast.service.UiState

class PlayerActivity : TvActivity() {
    private val connection = CastConnection(this)
    private val main = Handler(Looper.getMainLooper())
    private var stopObserving: (() -> Unit)? = null
    private var osdVisible = true

    private val hideOsd = Runnable {
        if (findViewById<TextView>(R.id.hints).tag != "error") {
            setOsdVisible(false)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (osdVisible) paintTransport()
            main.postDelayed(this, 250)
        }
    }

    private val hideVolume = Runnable {
        findViewById<TextView>(R.id.volume).visibility = View.GONE
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<ImageButton>(R.id.play_pause).setOnClickListener {
            connection.api?.togglePlay()
            reveal()
        }
        reveal()
    }

    override fun onStart() {
        super.onStart()
        connection.bind { api ->
            findViewById<PlayerView>(R.id.player_view).player = api.player()
            stopObserving?.invoke()
            stopObserving = api.observe { render(it) }
        }
        main.post(tick)
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        main.removeCallbacks(hideOsd)
        stopObserving?.invoke()
        stopObserving = null
        findViewById<PlayerView>(R.id.player_view).player = null
        connection.unbind()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val handled = code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_STOP ||
            code == KeyEvent.KEYCODE_BACK
        if (!handled) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (event.repeatCount > 0 &&
            code != KeyEvent.KEYCODE_DPAD_LEFT &&
            code != KeyEvent.KEYCODE_DPAD_RIGHT &&
            code != KeyEvent.KEYCODE_DPAD_UP &&
            code != KeyEvent.KEYCODE_DPAD_DOWN
        ) {
            return true
        }
        val api = connection.api
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> api?.seekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> api?.seekBy(10_000)
            KeyEvent.KEYCODE_DPAD_UP -> {
                api?.adjustVolume(5)
                showVolume()
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                api?.adjustVolume(-5)
                showVolume()
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            -> api?.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> api?.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> api?.togglePlay()
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_MEDIA_STOP -> {
                api?.stopPlayback()
                finish()
                return true
            }
        }
        reveal()
        return true
    }

    private fun render(state: UiState) {
        if (!state.showPlayer) {
            finish()
            return
        }
        findViewById<TextView>(R.id.source_line).text = state.sourceLine
        findViewById<TextView>(R.id.video_title).text =
            state.title.ifBlank { getString(R.string.opening) }
        val hints = findViewById<TextView>(R.id.hints)
        if (state.error != null) {
            hints.tag = "error"
            hints.text = state.error
            hints.setTextColor(getColor(R.color.danger))
            setOsdVisible(true)
            main.removeCallbacks(hideOsd)
        } else if (hints.tag == "error") {
            hints.tag = null
            hints.text = getString(R.string.remote_hints)
            hints.setTextColor(getColor(R.color.muted))
            reveal()
        }
        paintTransport()
    }

    private fun paintTransport() {
        val player = connection.api?.player() ?: return
        val playing = player.isPlaying ||
            (player.playWhenReady && player.playbackState == Player.STATE_BUFFERING)
        findViewById<ImageButton>(R.id.play_pause).setImageResource(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play,
        )
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        findViewById<TextView>(R.id.position).text = DlnaTime.formatClock(position)
        findViewById<TextView>(R.id.duration).text = DlnaTime.formatClock(duration)
        findViewById<ThinSeekBar>(R.id.progress).fraction =
            if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    }

    private fun reveal() {
        setOsdVisible(true)
        main.removeCallbacks(hideOsd)
        if (findViewById<TextView>(R.id.hints).tag != "error") {
            main.postDelayed(hideOsd, 5_000)
        }
    }

    private fun setOsdVisible(visible: Boolean) {
        osdVisible = visible
        findViewById<View>(R.id.osd).visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showVolume() {
        val volume = connection.api?.snapshot()?.volume ?: return
        val label = findViewById<TextView>(R.id.volume)
        label.visibility = View.VISIBLE
        label.text = getString(R.string.volume_label, volume)
        main.removeCallbacks(hideVolume)
        main.postDelayed(hideVolume, 1_500)
    }
}
