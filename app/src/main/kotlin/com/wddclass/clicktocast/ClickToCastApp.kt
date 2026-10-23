package com.wddclass.clicktocast

import android.app.Application
import com.wddclass.clicktocast.dlna.DmrLog

class ClickToCastApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DmrLog.out = { message -> android.util.Log.i("ClickToCast", message) }
    }
}
