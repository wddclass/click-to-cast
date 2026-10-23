package com.wddclass.clicktocast.ui

import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
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
        val surfaceView = findViewById<SurfaceView>(R.id.mirror_surface)
        val session = MirrorSession.active
        session?.bindStatus(status)
        session?.onPresented = { status.post { status.visibility = android.view.View.GONE } }
        session?.onSized = { width, height -> surfaceView.post { fitSurface(surfaceView, width, height) } }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                MirrorSession.active?.setSurface(holder.surface)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                MirrorSession.active?.clearSurface()
            }
        })
    }

    private fun fitSurface(view: SurfaceView, videoWidth: Int, videoHeight: Int) {
        val parent = view.parent as? FrameLayout ?: return
        val screenW = parent.width
        val screenH = parent.height
        if (screenW <= 0 || screenH <= 0 || videoWidth <= 0 || videoHeight <= 0) return
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val screenAspect = screenW.toFloat() / screenH.toFloat()
        val width: Int
        val height: Int
        if (videoAspect > screenAspect) {
            width = screenW
            height = (screenW / videoAspect).toInt()
        } else {
            height = screenH
            width = (screenH * videoAspect).toInt()
        }
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.width = width
        params.height = height
        params.gravity = Gravity.CENTER
        view.layoutParams = params
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
