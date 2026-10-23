package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.TextView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.Upnp
import com.wddclass.clicktocast.prefs.ReceiverSettings

class SettingsActivity : TvActivity() {
    private val connection = CastConnection(this)
    private lateinit var settings: ReceiverSettings
    private lateinit var name: EditText
    private var autoStart = true
    private var alwaysShowNet = false
    private var browserUa = true
    private var pendingApply = false
    private var writingName = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = ReceiverSettings(this)
        name = findViewById(R.id.name_input)
        val auto = findViewById<TextView>(R.id.auto_start)
        val alwaysNet = findViewById<TextView>(R.id.always_net)
        val browser = findViewById<TextView>(R.id.ua_browser)
        val player = findViewById<TextView>(R.id.ua_player)
        val restore = findViewById<TextView>(R.id.restore_name)
        val stop = findViewById<TextView>(R.id.stop_service)
        val overlay = findViewById<TextView>(R.id.overlay_grant)
        Pills.attach(auto, alwaysNet, browser, player, restore, stop, overlay)
        overlay.setOnClickListener { OverlayGuide.open(this) }

        autoStart = settings.autoStart
        alwaysShowNet = settings.alwaysShowNetSpeed
        browserUa = settings.browserUserAgent
        writingName = true
        name.setText(settings.deviceName.ifBlank { Upnp.DEFAULT_FRIENDLY_NAME })
        writingName = false
        fun paint() {
            auto.text = getString(if (autoStart) R.string.auto_start_on else R.string.auto_start_off)
            auto.isSelected = autoStart
            alwaysNet.text = getString(if (alwaysShowNet) R.string.always_net_on else R.string.always_net_off)
            alwaysNet.isSelected = alwaysShowNet
            browser.isSelected = browserUa
            player.isSelected = !browserUa
        }
        paint()
        name.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (!writingName) commit()
            }
        })
        auto.setOnClickListener {
            autoStart = !autoStart
            paint()
            commit()
        }
        alwaysNet.setOnClickListener {
            alwaysShowNet = !alwaysShowNet
            paint()
            commit()
        }
        browser.setOnClickListener {
            browserUa = true
            paint()
            commit()
        }
        player.setOnClickListener {
            browserUa = false
            paint()
            commit()
        }
        restore.setOnClickListener {
            writingName = true
            name.setText(Upnp.DEFAULT_FRIENDLY_NAME)
            writingName = false
            commit()
        }
        stop.setOnClickListener {
            connection.api?.shutdown()
            finishAffinity()
        }
        auto.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        paintOverlay()
    }

    override fun onStart() {
        super.onStart()
        connection.bind {
            if (pendingApply) commit()
        }
    }

    private fun commit() {
        val chosen = name.text?.toString()?.trim().orEmpty().ifBlank { Upnp.DEFAULT_FRIENDLY_NAME }
        settings.deviceName = chosen
        settings.autoStart = autoStart
        settings.alwaysShowNetSpeed = alwaysShowNet
        settings.browserUserAgent = browserUa
        val api = connection.api
        if (api == null) {
            pendingApply = true
            return
        }
        pendingApply = false
        api.applySettings(chosen, autoStart, browserUa)
    }

    private fun paintOverlay() {
        val allowed = OverlayGuide.allowed(this)
        findViewById<TextView>(R.id.overlay_status).text =
            getString(if (allowed) R.string.overlay_on else R.string.overlay_off)
        findViewById<TextView>(R.id.overlay_grant).visibility = if (allowed) View.GONE else View.VISIBLE
    }

    override fun onStop() {
        commit()
        connection.unbind()
        super.onStop()
    }
}
