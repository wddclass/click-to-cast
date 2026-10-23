package com.wddclass.clicktocast.ui

import android.app.Activity
import android.os.Build
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/** Keeps a 1920-wide television from rendering the layout like a dense phone. */
object TvMetrics {
    fun apply(activity: Activity) {
        val metrics = activity.resources.displayMetrics
        if (metrics.widthPixels < 1280) return
        val target = metrics.widthPixels / 960f
        if (target > 0f && metrics.density < target * 0.9f) {
            metrics.density = target
            metrics.scaledDensity = target
            metrics.densityDpi = (160f * target).toInt()
        }
    }
}

object Pills {
    fun attach(vararg views: View) {
        views.forEach { view ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                view.defaultFocusHighlightEnabled = false
            }
            // Scaling on focus draws outside the chip and ScrollView clips the left edge.
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }
}

open class TvActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        TvMetrics.apply(this)
        super.onCreate(savedInstanceState)
    }
}
