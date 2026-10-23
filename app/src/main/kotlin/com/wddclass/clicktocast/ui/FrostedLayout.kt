package com.wddclass.clicktocast.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/**
 * Capsule whose glass plate sits behind whatever you put inside.
 * The plate is excluded from wrap_content measurement so it can fill the chip
 * without stretching it to the screen.
 */
class FrostedLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {
    private val plate = View(context)

    init {
        clipChildren = false
        clipToPadding = false
        plate.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        plate.isFocusable = false
        plate.isClickable = false
        plate.isDuplicateParentStateEnabled = true
        plate.layoutParams = LayoutParams(0, 0)
        addView(plate)
        FrostedGlass.apply(plate)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        plate.measure(
            MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        plate.layout(0, 0, width, height)
    }
}
