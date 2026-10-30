package com.dualframe.camera

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout that is always as tall as it is wide (gallery tiles). */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
