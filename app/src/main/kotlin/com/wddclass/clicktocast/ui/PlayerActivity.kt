package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.wddclass.clicktocast.dlna.NetSpeed
import com.wddclass.clicktocast.dlna.PlaybackRates
import com.wddclass.clicktocast.prefs.ReceiverSettings
import com.wddclass.clicktocast.service.UiState

class PlayerActivity : TvActivity() {
    private val connection = CastConnection(this)
    private val main = Handler(Looper.getMainLooper())
    private var stopObserving: (() -> Unit)? = null
    private var osdVisible = true
    private var userHidOsd = false
    private var seenSession = -1L
    private var exitArmedUntil = 0L
    private var live = false
    private var alwaysShowNetSpeed = false

    private val hideOsd = Runnable {
        if (!shouldHoldOsd()) setOsdVisible(false)
    }

    private val hideExit = Runnable { disarmExit() }

    private val tick = object : Runnable {
        override fun run() {
            paintNet()
            if (osdVisible) paintTransport()
            main.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        readNetPref()
        findViewById<ImageButton>(R.id.play_pause).setOnClickListener {
            connection.api?.togglePlay()
            reveal()
        }
        findViewById<TextView>(R.id.speed_button).setOnClickListener {
            if (live) return@setOnClickListener
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
        findViewById<ImageButton>(R.id.play_pause).requestFocus()
        reveal()
    }

    override fun onResume() {
        super.onResume()
        readNetPref()
        paintNet()
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
        main.removeCallbacks(hideExit)
        stopObserving?.invoke()
        stopObserving = null
        findViewById<PlayerView>(R.id.player_view).player = null
        connection.unbind()
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
            if (speedMenuOpen()) {
                closeSpeedMenu(focusButton = true)
                disarmExit()
                return true
            }
            if (osdVisible) {
                hideOsdByUser()
                disarmExit()
                return true
            }
            val now = SystemClock.uptimeMillis()
            if (now <= exitArmedUntil) {
                disarmExit()
                connection.api?.stopPlayback()
                finish()
                return true
            }
            exitArmedUntil = now + 2_000L
            findViewById<View>(R.id.exit_hint).visibility = View.VISIBLE
            main.removeCallbacks(hideExit)
            main.postDelayed(hideExit, 2_000L)
            return true
        }
        if (code == KeyEvent.KEYCODE_MEDIA_STOP) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            connection.api?.stopPlayback()
            finish()
            return true
        }
        if (!isTransportKey(code)) return super.dispatchKeyEvent(event)
        if (!osdVisible) return hiddenTransport(event, code)
        if (speedMenuOpen() && focusInSpeedMenu()) {
            if (isDirectional(code)) {
                if (event.action == KeyEvent.ACTION_DOWN) reveal()
                return super.dispatchKeyEvent(event)
            }
            if (isOk(code)) {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                currentFocus?.performClick()
                return true
            }
        }
        val onSeekBar = currentFocus?.id == R.id.progress
        if (!live && onSeekBar && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            val delta = if (code == KeyEvent.KEYCODE_DPAD_LEFT) -10_000L else 10_000L
            connection.api?.seekBy(delta)
            reveal()
            return true
        }
        if (isDirectional(code)) {
            if (event.action == KeyEvent.ACTION_DOWN) reveal()
            return super.dispatchKeyEvent(event)
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        val api = connection.api
        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> if (!live && currentFocus?.id == R.id.speed_button) openSpeedMenu() else api?.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> api?.togglePlay()
        }
        reveal()
        return true
    }

    private fun hiddenTransport(event: KeyEvent, code: Int): Boolean {
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            reveal()
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (live && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            reveal()
            return true
        }
        when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> connection.api?.seekBy(-10_000)
            KeyEvent.KEYCODE_DPAD_RIGHT -> connection.api?.seekBy(10_000)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            -> connection.api?.togglePlay()
        }
        reveal()
        return true
    }

    private fun isTransportKey(code: Int): Boolean = isDirectional(code) ||
        code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
        code == KeyEvent.KEYCODE_MEDIA_PLAY ||
        code == KeyEvent.KEYCODE_MEDIA_PAUSE

    private fun isOk(code: Int): Boolean =
        code == KeyEvent.KEYCODE_DPAD_CENTER ||
            code == KeyEvent.KEYCODE_ENTER ||
            code == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun isDirectional(code: Int): Boolean =
        code == KeyEvent.KEYCODE_DPAD_LEFT ||
            code == KeyEvent.KEYCODE_DPAD_RIGHT ||
            code == KeyEvent.KEYCODE_DPAD_UP ||
            code == KeyEvent.KEYCODE_DPAD_DOWN

    private fun render(state: UiState) {
        if (!state.showPlayer) {
            finish()
            return
        }
        if (state.session != seenSession) {
            seenSession = state.session
            userHidOsd = false
            closeSpeedMenu(focusButton = false)
            findViewById<ThinSeekBar>(R.id.progress).cancelScrub()
        }
        findViewById<TextView>(R.id.source_line).text = state.sourceLine
        findViewById<TextView>(R.id.video_title).text =
            state.title.ifBlank { getString(R.string.opening) }
        live = state.live
        paintSpeed(state.speed)
        val hints = findViewById<TextView>(R.id.hints)
        if (state.error != null) {
            hints.tag = "error"
            hints.text = state.error
            hints.setTextColor(getColor(R.color.danger))
            userHidOsd = false
            setOsdVisible(true)
            main.removeCallbacks(hideOsd)
        } else if (hints.tag == "error") {
            hints.tag = null
            hints.text = getString(if (live) R.string.live_hints else R.string.remote_hints)
            hints.setTextColor(getColor(R.color.muted))
            reveal()
        } else {
            hints.text = getString(if (live) R.string.live_hints else R.string.remote_hints)
            if (state.playing) {
                if (!userHidOsd) armHide()
            } else if (!userHidOsd) {
                main.removeCallbacks(hideOsd)
                setOsdVisible(true)
            }
        }
        applyLiveChrome()
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
        if (live) return
        val player = connection.api?.player()
        val duration = player?.duration ?: -1L
        val ms = if (duration > 0) (fraction * duration).toLong() else 0L
        findViewById<TextView>(R.id.position).text = DlnaTime.formatClock(ms)
        if (finished && duration > 0) connection.api?.seekTo(ms)
        reveal()
    }

    private fun reveal() {
        userHidOsd = false
        disarmExit()
        setOsdVisible(true)
        if (!focusedOnControl()) findViewById<View>(R.id.play_pause).requestFocus()
        armHide()
    }

    private fun disarmExit() {
        exitArmedUntil = 0L
        main.removeCallbacks(hideExit)
        findViewById<View>(R.id.exit_hint)?.visibility = View.GONE
    }

    private fun hideOsdByUser() {
        userHidOsd = true
        main.removeCallbacks(hideOsd)
        findViewById<View>(R.id.speed_row).visibility = View.GONE
        setOsdVisible(false)
    }

    private fun focusInSpeedMenu(): Boolean {
        val row = findViewById<LinearLayout>(R.id.speed_row)
        return currentFocus?.parent == row
    }

    private fun focusedOnControl(): Boolean {
        val id = currentFocus?.id ?: return false
        if (id == R.id.play_pause || id == R.id.speed_button || id == R.id.progress) return true
        val row = findViewById<LinearLayout>(R.id.speed_row)
        for (index in 0 until row.childCount) {
            if (row.getChildAt(index).id == id) return true
        }
        return false
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
        applyLiveChrome()
    }

    private fun readNetPref() {
        alwaysShowNetSpeed = ReceiverSettings(this).alwaysShowNetSpeed
    }

    private fun paintNet() {
        val view = findViewById<TextView>(R.id.net_speed) ?: return
        view.text = NetSpeed.format(connection.api?.downloadBytesPerSecond() ?: 0L)
        view.visibility = if (osdVisible || alwaysShowNetSpeed) View.VISIBLE else View.GONE
    }

    private fun applyLiveChrome() {
        val play = findViewById<View>(R.id.play_pause) ?: return
        val speed = findViewById<View>(R.id.speed_button)
        val position = findViewById<View>(R.id.position)
        val duration = findViewById<View>(R.id.duration)
        val bar = findViewById<View>(R.id.progress)
        val inline = findViewById<View>(R.id.live_inline)
        val floating = findViewById<View>(R.id.live_badge)
        if (live) {
            if (speedMenuOpen()) closeSpeedMenu(focusButton = false)
            speed.visibility = View.GONE
            position.visibility = View.GONE
            duration.visibility = View.GONE
            bar.visibility = View.GONE
            inline.visibility = if (osdVisible) View.VISIBLE else View.GONE
            floating.visibility = if (osdVisible) View.GONE else View.VISIBLE
            play.nextFocusRightId = play.id
            play.nextFocusDownId = play.id
            val focused = currentFocus?.id
            if (focused == R.id.speed_button || focused == R.id.progress || focusInSpeedMenu()) {
                play.requestFocus()
            }
        } else {
            speed.visibility = View.VISIBLE
            position.visibility = View.VISIBLE
            duration.visibility = View.VISIBLE
            bar.visibility = View.VISIBLE
            inline.visibility = View.GONE
            floating.visibility = View.GONE
            play.nextFocusRightId = R.id.speed_button
            play.nextFocusDownId = R.id.progress
        }
        paintNet()
    }

    private fun speedMenuOpen(): Boolean =
        findViewById<View>(R.id.speed_row).visibility == View.VISIBLE

    private fun openSpeedMenu() {
        if (live) return
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
        val padH = (14 * density).toInt()
        val padV = (6 * density).toInt()
        val gap = (8 * density).toInt()
        val chips = ArrayList<TextView>()
        PlaybackRates.steps.forEach { rate ->
            val label = PlaybackRates.label(rate) ?: return@forEach
            val chip = TextView(this)
            chip.id = View.generateViewId()
            chip.text = getString(R.string.speed_choice, label)
            chip.tag = label
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
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
            if (chips.isNotEmpty()) params.topMargin = gap
            row.addView(chip, params)
            chips += chip
        }
        chips.forEachIndexed { index, chip ->
            chip.nextFocusUpId = chips.getOrNull(index - 1)?.id ?: chip.id
            chip.nextFocusDownId = chips.getOrNull(index + 1)?.id ?: chip.id
            chip.nextFocusLeftId = chip.id
            chip.nextFocusRightId = chip.id
        }
        Pills.attach(*chips.toTypedArray())
    }
}
