package com.wddclass.clicktocast.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.wddclass.clicktocast.prefs.ReceiverSettings

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        val relevant = action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON"
        if (!relevant) return
        if (!ReceiverSettings(context).autoStart) return
        ContextCompat.startForegroundService(context, Intent(context, CastReceiverService::class.java))
    }
}
