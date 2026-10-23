package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.TextView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.airplay.MirrorSession

/** Full-screen H.264 surface for an AirPlay screen mirror. Closed by TEARDOWN or Back. */
class MirrorActivity : TvActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status = findViewById<TextView>(R.id.mirror_status)
        val session = MirrorSession.active
        session?.bindStatus(status)
        session?.onPresented = { status.post { status.visibility = android.view.View.GONE } }
        findViewById<SurfaceView>(R.id.mirror_surface).holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                MirrorSession.active?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MirrorSession.active?.clearSurface()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        current = this
    }

    override fun onStop() {
        if (current == this) current = null
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                MirrorSession.active?.stop("back")
                finish()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        @Volatile
        private var current: MirrorActivity? = null

        fun finishActive() {
            val activity = current ?: return
            activity.runOnUiThread { activity.finish() }
        }
    }
}
