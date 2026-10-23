package com.wddclass.clicktocast.prefs

import android.content.Context
import com.wddclass.clicktocast.dlna.Upnp
import java.util.UUID

class ReceiverSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var deviceName: String
        get() = prefs.getString(KEY_NAME, DEFAULT_NAME).orEmpty().trim().ifBlank { DEFAULT_NAME }.take(32)
        set(value) {
            prefs.edit().putString(KEY_NAME, value.trim().take(32).ifBlank { DEFAULT_NAME }).apply()
        }

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO, value).apply()
        }

    var browserUserAgent: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_UA, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BROWSER_UA, value).apply()
        }

    var overlayHintDismissed: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_DISMISS, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OVERLAY_DISMISS, value).apply()
        }

    val deviceUuid: String
        get() {
            val existing = prefs.getString(KEY_UUID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_UUID, created).apply()
            return created
        }

    fun userAgent(): String = if (browserUserAgent) BROWSER_UA else PLAYER_UA

    companion object {
        const val DEFAULT_NAME = Upnp.DEFAULT_FRIENDLY_NAME
        const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 11; Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        const val PLAYER_UA = "ClickToCast/0.1.0 (Android TV)"

        private const val PREFS = "clicktocast"
        private const val KEY_NAME = "device_name"
        private const val KEY_AUTO = "auto_start"
        private const val KEY_BROWSER_UA = "browser_ua"
        private const val KEY_UUID = "uuid"
        private const val KEY_OVERLAY_DISMISS = "overlay_hint_dismissed"
    }
}
