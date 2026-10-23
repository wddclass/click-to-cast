package com.wddclass.clicktocast.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.View
import com.wddclass.clicktocast.R
import com.wddclass.clicktocast.dlna.DmrLog

/**
 * Shared frosted-glass chips. Later screens should use [FrostedLayout] or call
 * [apply] on a plate drawn behind the label.
 *
 * The plate is always [R.drawable.bg_frosted]: a translucent fill and a soft
 * border, which is the whole look on Android 11. API 31 and newer also blur
 * that plate. The label has to stay a separate view; blurring the text view
 * would smear the glyphs. A video SurfaceView is composited outside the view
 * tree, so the public blur samples the plate, and the picture shows through
 * the translucent fill.
 */
object FrostedGlass {
    fun apply(plate: View, background: Int = R.drawable.bg_frosted) {
        plate.setBackgroundResource(background)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            DmrLog.i("frosted chip: translucent material")
            return
        }
        try {
            val radius = 4f * plate.resources.displayMetrics.density
            plate.setRenderEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL),
            )
            DmrLog.i("frosted chip: blur radius=$radius")
        } catch (error: Throwable) {
            plate.setRenderEffect(null)
            DmrLog.i("frosted chip blur skipped: ${error.javaClass.simpleName}")
        }
    }
}

/** The red Live mark belongs to the control bar and leaves with it. */
internal object LiveChrome {
    fun badgeVisible(live: Boolean, controlsVisible: Boolean): Boolean = live && controlsVisible
}
