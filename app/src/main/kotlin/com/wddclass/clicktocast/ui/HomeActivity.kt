package com.wddclass.clicktocast.ui

import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.Upnp
import com.wddclass.clicktocast.prefs.ReceiverSettings
import com.wddclass.clicktocast.service.CastReceiverService
import com.wddclass.clicktocast.service.StatusTone
import com.wddclass.clicktocast.service.UiState

class HomeActivity : TvActivity() {
    private val connection = CastConnection(this)
    private var stopObserving: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        val settings = findViewById<TextView>(R.id.open_settings)
        val history = findViewById<TextView>(R.id.open_history)
        val about = findViewById<TextView>(R.id.open_about)
        val overlay = findViewById<TextView>(R.id.overlay_open)
        val dismiss = findViewById<TextView>(R.id.overlay_dismiss)
        Pills.attach(settings, history, about, overlay, dismiss)
        overlay.setOnClickListener { OverlayGuide.open(this) }
        dismiss.setOnClickListener {
            ReceiverSettings(this).overlayHintDismissed = true
            paintOverlay()
        }
        settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        history.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        about.setOnClickListener { startActivity(Intent(this, AboutActivity::class.java)) }
        settings.requestFocus()
        render(UiState())
    }

    override fun onResume() {
        super.onResume()
        paintOverlay()
    }

    override fun onStart() {
        super.onStart()
        connection.bind { api ->
            stopObserving?.invoke()
            stopObserving = api.observe { render(it) }
        }
    }

    override fun onStop() {
        stopObserving?.invoke()
        stopObserving = null
        connection.unbind()
        super.onStop()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun render(state: UiState) {
        val name = state.deviceName.ifBlank { Upnp.DEFAULT_FRIENDLY_NAME }
        findViewById<TextView>(R.id.device_name).text = name
        findViewById<TextView>(R.id.status).text = state.statusLine
        findViewById<TextView>(R.id.ip_address).text =
            state.hostAddress.ifBlank { getString(R.string.ip_missing) }
        findViewById<TextView>(R.id.step_pick).text = getString(R.string.step_pick, name)
        val color = when (state.statusTone) {
            StatusTone.ERROR -> getColor(R.color.danger)
            StatusTone.ACTIVE -> getColor(R.color.teal)
            StatusTone.READY -> getColor(R.color.teal)
        }
        findViewById<TextView>(R.id.status).setTextColor(color)
        (findViewById<android.view.View>(R.id.status_dot).background as? GradientDrawable)?.setColor(color)
    }

    private fun paintOverlay() {
        val show = !OverlayGuide.allowed(this) && !ReceiverSettings(this).overlayHintDismissed
        findViewById<View>(R.id.overlay_banner).visibility = if (show) View.VISIBLE else View.GONE
    }
}
