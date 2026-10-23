package com.wddclass.clicktocast.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Focusable progress bar. A finger drag previews the position and seeks when it lifts.
 * Remote left and right seek only while this bar is focused. Otherwise they move focus.
 */
class ThinSeekBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var onScrub: ((fraction: Float, finished: Boolean) -> Unit)? = null

    var scrubbing: Boolean = false
        private set

    private var shown = 0f

    var fraction: Float
        get() = shown
        set(value) {
            if (scrubbing) return
            shown = value.coerceIn(0f, 1f)
            invalidate()
        }

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FFFFFF }
    private val played = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val focus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF24FEC1.toInt()
    }

    init {
        isFocusable = true
        isClickable = true
    }

    fun cancelScrub() {
        scrubbing = false
        invalidate()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val barHeight = 3f * density
        val knob = (if (isFocused || scrubbing) 8f else 6f) * density
        val cy = height / 2f
        val left = 10f * density
        val right = max(left + 1f, width - left)
        val y = cy - barHeight / 2f
        canvas.drawRoundRect(left, y, right, y + barHeight, barHeight, barHeight, track)
        val head = left + (right - left) * shown
        canvas.drawRoundRect(left, y, head, y + barHeight, barHeight, barHeight, played)
        canvas.drawCircle(head, cy, knob, played)
        if (isFocused) {
            focus.strokeWidth = 2f * density
            val inset = 3f * density
            val radius = 10f * density
            canvas.drawRoundRect(inset, inset, width - inset, height - inset, radius, radius, focus)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                scrubbing = true
                moveTo(event.x, finished = false)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scrubbing) return false
                moveTo(event.x, finished = false)
                return true
            }
            MotionEvent.ACTION_UP -> {
                moveTo(event.x, finished = true)
                scrubbing = false
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                scrubbing = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveTo(x: Float, finished: Boolean) {
        shown = fractionAt(x)
        invalidate()
        onScrub?.invoke(shown, finished)
    }

    private fun fractionAt(x: Float): Float {
        val density = resources.displayMetrics.density
        val left = 10f * density
        val right = max(left + 1f, width - left)
        return ((x - left) / (right - left)).coerceIn(0f, 1f)
    }
}
