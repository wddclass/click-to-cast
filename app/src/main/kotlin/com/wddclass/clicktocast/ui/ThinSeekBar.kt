package com.wddclass.clicktocast.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/** A thin white progress line with a round knob. Display only; seeking is done with the remote. */
class ThinSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var fraction: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val barHeight = 3f * density
        val knob = 6f * density
        val cy = height / 2f
        val left = knob
        val right = max(left, width - knob)
        val y = cy - barHeight / 2f
        canvas.drawRoundRect(left, y, right, y + barHeight, barHeight, barHeight, track)
        val head = left + (right - left) * fraction
        canvas.drawRoundRect(left, y, head, y + barHeight, barHeight, barHeight, played)
        canvas.drawCircle(head, cy, knob, played)
    }
}
