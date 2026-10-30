package com.dualframe.camera

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider

/** Any colour: hue, saturation, brightness and opacity sliders, a hex field and quick picks. */
object ColorPicker {
    fun show(context: Context, title: String, initial: Int, onPick: (Int) -> Unit) {
        fun dp(v: Float) = Glass.dp(context, v).toInt()
        fun flow() = ChipGroup(context).apply {
            chipSpacingHorizontal = dp(8f)
            chipSpacingVertical = dp(8f)
        }
        val hsv = FloatArray(3)
        Color.colorToHSV(initial, hsv)
        var alpha = Color.alpha(initial)
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(8f), dp(24f), 0)
        }
        val swatch = View(context)
        val hex = EditText(context).apply {
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            hint = "#AARRGGBB"
        }
        var updating = false
        fun current() = Color.HSVToColor(alpha, hsv)
        fun refresh(fromHex: Boolean = false) {
            swatch.background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(current())
                setStroke(dp(1f), Color.argb(120, 255, 255, 255))
            }
            if (!fromHex) {
                updating = true
                hex.setText(String.format("#%08X", current()))
                updating = false
            }
        }
        box.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48f)))
        val sliders = mutableListOf<Slider>()
        fun colorSlider(label: String, max: Float, value: Float, onChange: (Float) -> Unit) {
            box.addView(TextView(context).apply {
                text = label
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding(0, dp(8f), 0, 0)
            })
            box.addView(Slider(context).apply {
                valueFrom = 0f
                valueTo = max
                this.value = value.coerceIn(0f, max)
                addOnChangeListener { _, v, fromUser -> if (fromUser) { onChange(v); refresh() } }
            }.also { sliders += it })
        }
        colorSlider("Hue", 360f, hsv[0]) { hsv[0] = it }
        colorSlider("Saturation", 100f, hsv[1] * 100f) { hsv[1] = it / 100f }
        colorSlider("Brightness", 100f, hsv[2] * 100f) { hsv[2] = it / 100f }
        colorSlider("Opacity", 100f, alpha / 2.55f) { alpha = (it * 2.55f).toInt().coerceIn(0, 255) }
        // Quick picks: the colours trending captions use most.
        val quick = flow().apply { setPadding(0, dp(8f), 0, 0) }
        listOf(
            Color.WHITE, Color.BLACK, 0xFFFFE14D.toInt(), 0xFF39FF7A.toInt(), 0xFF4DE1FF.toInt(),
            0xFFFF2BD6.toInt(), 0xFFFF3B6B.toInt(), 0xFFFF8A3D.toInt(), 0xFFE8C88C.toInt(), 0xFF7C5CFF.toInt(),
        ).forEach { c ->
            quick.addView(View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                    setStroke(dp(1f), Color.argb(120, 255, 255, 255))
                }
                setOnClickListener {
                    Color.colorToHSV(c, hsv)
                    sliders[0].value = hsv[0]
                    sliders[1].value = hsv[1] * 100f
                    sliders[2].value = hsv[2] * 100f
                    refresh()
                }
            }, ViewGroup.LayoutParams(dp(30f), dp(30f)))
        }
        box.addView(quick)
        box.addView(hex)
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                if (updating) return
                val c = runCatching { Color.parseColor(s.toString().trim()) }.getOrNull() ?: return
                Color.colorToHSV(c, hsv)
                alpha = Color.alpha(c)
                sliders[0].value = hsv[0]
                sliders[1].value = hsv[1] * 100f
                sliders[2].value = hsv[2] * 100f
                sliders[3].value = alpha / 2.55f
                refresh(fromHex = true)
            }
        })
        refresh()
        MaterialAlertDialogBuilder(context)
            .setTitle("$title colour")
            .setView(NestedScrollView(context).apply { addView(box) })
            .setPositiveButton("Use") { _, _ -> onPick(current()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

}
