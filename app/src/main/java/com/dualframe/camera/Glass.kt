package com.dualframe.camera

import android.app.Dialog
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat

/** Small builders for the glass UI so every screen draws pills and labels the same way. */
object Glass {

    fun dp(context: Context, value: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)

    /** A rounded glass pill. Selected = solid warm white with ink text. */
    fun pill(context: Context, text: String, selected: Boolean = false, onClick: (() -> Unit)? = null) =
        TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            minHeight = dp(context, 40f).toInt()
            minWidth = dp(context, 48f).toInt()
            val h = dp(context, 14f).toInt()
            setPadding(h, 0, h, 0)
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_pill)
            setTextColor(ContextCompat.getColorStateList(context, R.color.pill_text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            letterSpacing = 0.02f
            isSelected = selected
            if (onClick != null) {
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }

    fun label(context: Context, text: String) = TextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.DF_Text_Label)
        this.text = text
    }

    fun section(context: Context, text: String) = TextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.DF_Text_Section)
        this.text = text
        setPadding(0, dp(context, 22f).toInt(), 0, dp(context, 8f).toInt())
    }

    fun caption(context: Context, text: String) = TextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.DF_Text_Caption)
        this.text = text
    }

    fun body(context: Context, text: String) = TextView(context).apply {
        TextViewCompat.setTextAppearance(this, R.style.DF_Text_Body)
        this.text = text
    }

    /** Frosted glass: blur whatever is behind a dialog or sheet (Android 12+, when the phone allows it). */
    fun blurBehind(dialog: Dialog, radiusDp: Float = 28f) {
        if (Build.VERSION.SDK_INT < 31) return
        val window = dialog.window ?: return
        val wm = window.context.getSystemService(WindowManager::class.java)
        if (wm?.isCrossWindowBlurEnabled != true) return
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        window.attributes = window.attributes.apply { blurBehindRadius = dp(window.context, radiusDp).toInt() }
    }

    /** Fade + slight lift, the one motion used everywhere. */
    fun show(view: View, visible: Boolean) {
        if (visible == (view.visibility == View.VISIBLE)) return
        view.animate().cancel()
        if (visible) {
            view.alpha = 0f
            view.translationY = dp(view.context, 8f)
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).translationY(0f).setDuration(180).start()
        } else {
            view.animate().alpha(0f).translationY(dp(view.context, 8f)).setDuration(140)
                .withEndAction {
                    view.visibility = View.GONE
                    view.alpha = 1f
                    view.translationY = 0f
                }.start()
        }
    }
}
