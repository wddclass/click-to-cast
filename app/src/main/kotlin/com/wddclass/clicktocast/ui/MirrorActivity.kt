package com.wddclass.clicktocast.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.airplay.MirrorSession
import com.wddclass.clicktocast.dlna.NetSpeed

/** Full-screen H.264 surface for an AirPlay screen mirror. */
class MirrorActivity : TvActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var osdVisible = false
    private var exitArmedUntil = 0L

    private val hideOsd = Runnable {
        osdVisible = false
        findViewById<View>(R.id.mirror_osd).visibility = View.GONE
    }
    private val hideExit = Runnable {
        exitArmedUntil = 0L
        findViewById<View>(R.id.exit_hint).visibility = View.GONE
    }
    private val paintChrome = object : Runnable {
        override fun run() {
            if (!osdVisible) return
            paintMirror()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mirror)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val status = findViewById<TextView>(R.id.mirror_status)
        val surfaceView = findViewById<SurfaceView>(R.id.mirror_surface)
        val stop = findViewById<TextView>(R.id.mirror_stop)
        Pills.attach(stop)
        stop.setOnClickListener { MirrorSession.active?.stop("结束镜像") }
        val session = MirrorSession.active
        session?.bindStatus(status)
        session?.onPresented = { status.post { status.visibility = View.GONE } }
        session?.onSized = { width, height ->
            surfaceView.post {
                fitSurface(surfaceView, width, height)
                if (osdVisible) paintMirror()
            }
        }
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
        main.removeCallbacks(hideOsd)
        main.removeCallbacks(hideExit)
        main.removeCallbacks(paintChrome)
        super.onStop()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK) {
            if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
            if (osdVisible) {
                hideOverlay()
                disarmExit()
                return true
            }
            val now = SystemClock.uptimeMillis()
            if (now <= exitArmedUntil) {
                disarmExit()
                MirrorSession.active?.stop("back")
                return true
            }
            exitArmedUntil = now + 2_000L
            findViewById<View>(R.id.exit_hint).visibility = View.VISIBLE
            main.removeCallbacks(hideExit)
            main.postDelayed(hideExit, 2_000L)
            return true
        }
        if (!isChromeKey(code)) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        if (osdVisible && isOk(code)) {
            findViewById<View>(R.id.mirror_stop).performClick()
            return true
        }
        showOverlay()
        return true
    }

    private fun showOverlay() {
        osdVisible = true
        findViewById<View>(R.id.mirror_osd).visibility = View.VISIBLE
        paintMirror()
        findViewById<View>(R.id.mirror_stop).requestFocus()
        main.removeCallbacks(hideOsd)
        main.postDelayed(hideOsd, 4_000L)
        main.removeCallbacks(paintChrome)
        main.postDelayed(paintChrome, 1_000L)
    }

    private fun hideOverlay() {
        osdVisible = false
        main.removeCallbacks(hideOsd)
        main.removeCallbacks(paintChrome)
        findViewById<View>(R.id.mirror_osd).visibility = View.GONE
    }

    private fun disarmExit() {
        exitArmedUntil = 0L
        main.removeCallbacks(hideExit)
        findViewById<View>(R.id.exit_hint).visibility = View.GONE
    }

    private fun paintMirror() {
        val (name, size, speed) = MirrorSession.active?.chrome() ?: Triple("iPhone", "", 0L)
        findViewById<TextView>(R.id.mirror_osd_title).text = getString(R.string.mirror_live, name)
        val rate = NetSpeed.format(speed)
        findViewById<TextView>(R.id.mirror_osd_detail).text = if (size.isBlank()) rate else "$size · $rate"
    }

    private fun isChromeKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_INFO,
        -> true
        else -> false
    }

    private fun isOk(code: Int): Boolean = code == KeyEvent.KEYCODE_DPAD_CENTER ||
        code == KeyEvent.KEYCODE_ENTER ||
        code == KeyEvent.KEYCODE_NUMPAD_ENTER

    companion object {
        @Volatile
        private var current: MirrorActivity? = null

        fun finishActive() {
            val activity = current ?: return
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                activity.startActivity(
                    Intent(activity, HomeActivity::class.java).addFlags(
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_NEW_TASK,
                    ),
                )
                activity.finish()
            }
        }
    }
}
