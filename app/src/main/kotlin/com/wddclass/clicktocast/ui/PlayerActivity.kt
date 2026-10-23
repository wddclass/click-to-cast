package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.view.WindowManager
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.DlnaTime
import com.wddclass.clicktocast.dlna.PlaybackRates
import com.wddclass.clicktocast.service.UiState

class PlayerActivity : TvActivity() {
    private val connection = CastConnection(this)
    private val main = Handler(Looper.getMainLooper())
    private var stopObserving: (() -> Unit)? = null
    private var osdVisible = true
    private var seenSession = -1L

    private val hideOsd = Runnable {
        if (!shouldHoldOsd()) setOsdVisible(false)
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
        findViewById<TextView>(R.id.speed_button).setOnClickListener {
            if (speedMenuOpen()) closeSpeedMenu(focusButton = true) else openSpeedMenu()
        }
        findViewById<PlayerView>(R.id.player_view).setOnClickListener { reveal() }
        findViewById<View>(R.id.osd).setOnClickListener {
            if (!shouldHoldOsd()) {
                main.removeCallbacks(hideOsd)
                setOsdVisible(false)
            }
        }
        val bar = findViewById<ThinSeekBar>(R.id.progress)
        bar.onScrub = { fraction, finished -> onScrub(fraction, finished) }
        buildSpeedChips()
        Pills.attach(findViewById(R.id.speed_button))
        bar.requestFocus()
        reveal()
    }

    override fun onStart() {
        super.onStart()
        connection.bind { api ->
            api.notePlayerVisible(true)
            findViewById<PlayerView>(R.id.player_view).player = api.player()
            stopObserving?.invoke()
            stopObserving = api.observe { render(it) }
        }
        main.post(tick)
    }

    override fun onStop() {
        connection.api?.notePlayerVisible(false)
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
        val menu = speedMenuOpen()
        val focusId = currentFocus?.id ?: View.NO_ID
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            if (menu && code == KeyEvent.KEYCODE_BACK) {
                closeSpeedMenu(focusButton = true)
                return true
            }
            connection.api?.stopPlayback()
            finish()
            return true
        }
        val handled = code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN ||
            code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY ||
            code == KeyEvent.KEYCODE_MEDIA_PAUSE
        if (!handled) return super.dispatchKeyEvent(event)
        if (menu && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN ||
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_NUMPAD_ENTER)
        ) {
            if (event.action == KeyEvent.ACTION_DOWN) reveal()
            return super.dispatchKeyEvent(event)
        }
        val betweenControls = focusId == R.id.play_pause || focusId == R.id.speed_button
        if (betweenControls && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action == KeyEvent.ACTION_DOWN) reveal()
            return super.dispatchKeyEvent(event)
        }
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
            -> when (focusId) {
                R.id.speed_button -> openSpeedMenu()
                R.id.play_pause -> api?.togglePlay()
                else -> api?.togglePlay()
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> api?.togglePlay()
        }
        reveal()
        return true
    }

    private fun render(state: UiState) {
        if (!state.showPlayer) {
            finish()
            return
        }
        if (state.session != seenSession) {
            seenSession = state.session
            closeSpeedMenu(focusButton = false)
            findViewById<ThinSeekBar>(R.id.progress).cancelScrub()
        }
        findViewById<TextView>(R.id.source_line).text = state.sourceLine
        findViewById<TextView>(R.id.video_title).text =
            state.title.ifBlank { getString(R.string.opening) }
        paintSpeed(state.speed)
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
        } else if (state.playing) {
            armHide()
        } else {
            main.removeCallbacks(hideOsd)
            setOsdVisible(true)
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
        val bar = findViewById<ThinSeekBar>(R.id.progress)
        if (bar.scrubbing) return
        val position = player.currentPosition.coerceAtLeast(0L)
        val duration = player.duration
        findViewById<TextView>(R.id.position).text = DlnaTime.formatClock(position)
        findViewById<TextView>(R.id.duration).text = DlnaTime.formatClock(duration)
        bar.fraction = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
    }

    private fun onScrub(fraction: Float, finished: Boolean) {
        val player = connection.api?.player()
        val duration = player?.duration ?: -1L
        val ms = if (duration > 0) (fraction * duration).toLong() else 0L
        findViewById<TextView>(R.id.position).text = DlnaTime.formatClock(ms)
        if (finished && duration > 0) connection.api?.seekTo(ms)
        reveal()
    }

    private fun reveal() {
        setOsdVisible(true)
        armHide()
    }

    private fun armHide() {
        main.removeCallbacks(hideOsd)
        if (!shouldHoldOsd()) main.postDelayed(hideOsd, 4_000)
    }

    private fun shouldHoldOsd(): Boolean {
        if (findViewById<TextView>(R.id.hints).tag == "error") return true
        if (speedMenuOpen()) return true
        val player = connection.api?.player() ?: return false
        if (player.playbackState == Player.STATE_ENDED) return true
        if (player.playbackState == Player.STATE_READY && !player.playWhenReady) return true
        return false
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

    private fun speedMenuOpen(): Boolean =
        findViewById<View>(R.id.speed_row).visibility == View.VISIBLE

    private fun openSpeedMenu() {
        val row = findViewById<LinearLayout>(R.id.speed_row)
        row.visibility = View.VISIBLE
        val current = connection.api?.snapshot()?.speed ?: "1"
        var target: View? = if (row.childCount > 0) row.getChildAt(0) else null
        for (index in 0 until row.childCount) {
            val chip = row.getChildAt(index)
            if (chip.tag == current) target = chip
        }
        target?.requestFocus()
        reveal()
    }

    private fun closeSpeedMenu(focusButton: Boolean) {
        val row = findViewById<View>(R.id.speed_row)
        if (row.visibility != View.VISIBLE) return
        row.visibility = View.GONE
        if (focusButton) findViewById<View>(R.id.speed_button).requestFocus()
        armHide()
    }

    private fun paintSpeed(speed: String) {
        findViewById<TextView>(R.id.speed_button).text = getString(R.string.speed_label, speed)
        val row = findViewById<LinearLayout>(R.id.speed_row)
        for (index in 0 until row.childCount) {
            row.getChildAt(index).isSelected = row.getChildAt(index).tag == speed
        }
    }

    private fun buildSpeedChips() {
        val row = findViewById<LinearLayout>(R.id.speed_row)
        val density = resources.displayMetrics.density
        val padH = (22 * density).toInt()
        val padV = (10 * density).toInt()
        val gap = (12 * density).toInt()
        val chips = ArrayList<TextView>()
        PlaybackRates.steps.forEach { rate ->
            val label = PlaybackRates.label(rate) ?: return@forEach
            val chip = TextView(this)
            chip.id = View.generateViewId()
            chip.text = getString(R.string.speed_choice, label)
            chip.tag = label
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            chip.setTextColor(getColorStateList(R.color.pill_text))
            chip.setBackgroundResource(R.drawable.bg_pill)
            chip.setPadding(padH, padV, padH, padV)
            chip.isFocusable = true
            chip.isClickable = true
            chip.gravity = android.view.Gravity.CENTER
            chip.setOnClickListener {
                connection.api?.setSpeed(rate.toFloat())
                closeSpeedMenu(focusButton = true)
            }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            params.marginEnd = gap
            row.addView(chip, params)
            chips += chip
        }
        chips.forEachIndexed { index, chip ->
            chip.nextFocusLeftId = chips.getOrNull(index - 1)?.id ?: chip.id
            chip.nextFocusRightId = chips.getOrNull(index + 1)?.id ?: chip.id
            chip.nextFocusUpId = R.id.speed_button
        }
        Pills.attach(*chips.toTypedArray())
    }
}
