package com.wddclass.clicktocast.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.wddclass.clicktocast.service.CastReceiverService

class CastConnection(private val activity: Activity) {
    var api: CastReceiverService.Api? = null
        private set

    private var ready: ((CastReceiverService.Api) -> Unit)? = null
    private var bound = false
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (!bound) return
            val boundApi = service as? CastReceiverService.Api ?: return
            api = boundApi
            ready?.invoke(boundApi)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            api = null
        }
    }

    fun bind(onReady: (CastReceiverService.Api) -> Unit) {
        ready = onReady
        val intent = Intent(activity, CastReceiverService::class.java)
        ContextCompat.startForegroundService(activity, intent)
        bound = activity.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        ready = null
        if (!bound) return
        bound = false
        runCatching { activity.unbindService(connection) }
        api = null
    }
}
