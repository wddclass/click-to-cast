package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.Upnp
import com.wddclass.clicktocast.prefs.ReceiverSettings

class SettingsActivity : TvActivity() {
    private val connection = CastConnection(this)
    private lateinit var settings: ReceiverSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = ReceiverSettings(this)
        val name = findViewById<EditText>(R.id.name_input)
        val auto = findViewById<TextView>(R.id.auto_start)
        val browser = findViewById<TextView>(R.id.ua_browser)
        val player = findViewById<TextView>(R.id.ua_player)
        val restore = findViewById<TextView>(R.id.restore_name)
        val save = findViewById<TextView>(R.id.save)
        val stop = findViewById<TextView>(R.id.stop_service)
        val overlay = findViewById<TextView>(R.id.overlay_grant)
        Pills.attach(auto, browser, player, restore, save, stop, overlay)
        overlay.setOnClickListener { OverlayGuide.open(this) }

        name.setText(settings.deviceName.ifBlank { Upnp.DEFAULT_FRIENDLY_NAME })
        var autoStart = settings.autoStart
        var browserUa = settings.browserUserAgent
        fun paint() {
            auto.text = getString(if (autoStart) R.string.auto_start_on else R.string.auto_start_off)
            auto.isSelected = autoStart
            browser.isSelected = browserUa
            player.isSelected = !browserUa
        }
        paint()
        auto.setOnClickListener {
            autoStart = !autoStart
            paint()
        }
        browser.setOnClickListener {
            browserUa = true
            paint()
        }
        player.setOnClickListener {
            browserUa = false
            paint()
        }
        restore.setOnClickListener {
            name.setText(Upnp.DEFAULT_FRIENDLY_NAME)
            name.requestFocus()
        }
        save.setOnClickListener {
            val chosen = name.text?.toString()?.trim().orEmpty().ifBlank { Upnp.DEFAULT_FRIENDLY_NAME }
            settings.deviceName = chosen
            settings.autoStart = autoStart
            settings.browserUserAgent = browserUa
            connection.api?.applySettings(chosen, autoStart, browserUa)
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        stop.setOnClickListener {
            connection.api?.shutdown()
            finishAffinity()
        }
        save.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        paintOverlay()
    }

    override fun onStart() {
        super.onStart()
        connection.bind { }
    }

    private fun paintOverlay() {
        val allowed = OverlayGuide.allowed(this)
        findViewById<TextView>(R.id.overlay_status).text =
            getString(if (allowed) R.string.overlay_on else R.string.overlay_off)
        findViewById<TextView>(R.id.overlay_grant).visibility = if (allowed) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        connection.unbind()
        super.onStop()
    }
}
