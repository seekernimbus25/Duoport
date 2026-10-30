package com.dualframe.camera

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/**
 * Caption style: presets, any font (bundled, the phone's, or imported), any colours, and the
 * trending effects. The preview at the top animates with the real renderer, inside a phone
 * frame that shows the safe area, so what you see is what gets burned in.
 */
class CaptionStyleSheet(
    private val context: Context,
    /** Asks the screen to open the file picker for a .ttf / .otf; it calls [onFontImported] back. */
    private val onImportFont: () -> Unit,
    /** Called after the sheet closes and the style is saved. */
    private val onClosed: () -> Unit = {},
) {
    private var style = CaptionStyle.load(context)
    private val dialog = BottomSheetDialog(context)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(8f), dp(20f), dp(40f))
    }
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val preview = ImageView(context)
    private var wide = false
    private var renderer = makeRenderer()
    private var animating = false
    private val startedAt = System.currentTimeMillis()

    private fun dp(v: Float) = Glass.dp(context, v).toInt()

    fun show() {
        root.addView(Glass.body(context, "Caption style").apply {
            setTextAppearance(R.style.DF_Text_Title)
            setPadding(0, dp(8f), 0, dp(10f))
        })
        val frame = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(16f).toFloat()
                setColor(Color.rgb(20, 20, 24))
            }
        }
        frame.addView(preview, FrameLayout.LayoutParams(dp(200f), dp(200f), Gravity.CENTER))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300f)))
        root.addView(flow().apply {
            setPadding(0, dp(8f), 0, 0)
            val tall = Glass.pill(context, "Tall 9:16", !wide)
            val w = Glass.pill(context, "Wide 16:9", wide)
            tall.setOnClickListener { wide = false; tall.isSelected = true; w.isSelected = false; layoutPreview() }
            w.setOnClickListener { wide = true; w.isSelected = true; tall.isSelected = false; layoutPreview() }
            addView(tall)
            addView(w)
        })
        root.addView(Glass.caption(
            context,
            "Dashed lines show the safe area: captions stay a little below the middle and clear of the edges, " +
                "the buttons down the right and the caption block at the bottom of Reels, TikTok and Shorts.",
        ).apply { setPadding(0, dp(6f), 0, 0) })
        root.addView(controls)
        buildControls()

        dialog.setContentView(NestedScrollView(context).apply { addView(root) })
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.85f).toInt()
        dialog.setOnShowListener {
            layoutPreview()
            animating = true
            tick()
        }
        dialog.setOnDismissListener {
            animating = false
            CaptionStyle.save(context, style)
            onClosed()
        }
        Glass.blurBehind(dialog)
        dialog.show()
    }

    /** Called by the screen after a font file was imported. */
    fun onFontImported(id: String) {
        update(style.copy(font = id))
    }

    // region Preview

    private fun makeRenderer() = CaptionRenderer(style, CaptionFonts.typeface(context, style.font), CaptionFonts.variation(style.font))

    private fun layoutPreview() {
        val h = dp(280f)
        val w = if (wide) minOf(dp(320f), (h * 16 / 9f).toInt()) else (h * 9 / 16f).toInt()
        val ph = if (wide) (w * 9 / 16f).toInt() else h
        preview.layoutParams = FrameLayout.LayoutParams(w, ph, Gravity.CENTER)
    }

    private val sample = listOf("Record", "once,", "post", "everywhere", "10x", "faster", "aaj", "ka", "plan", "ready")
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(110, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        strokeWidth = 2f
    }
    private val bg = Paint()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }

    private fun tick() {
        if (!animating) return
        val vw = if (wide) 960 else 540
        val vh = if (wide) 540 else 960
        val now = System.currentTimeMillis() - startedAt
        // A new word every ~450 ms, a new line every few words, like real speech.
        val perLine = if (style.wordsPerLine > 0) style.wordsPerLine else 4
        val wordIndex = ((now / 450) % sample.size).toInt()
        val lineStart = wordIndex / perLine * perLine
        val line = sample.subList(lineStart, minOf(sample.size, lineStart + perLine))
        val active = wordIndex - lineStart
        val phase = ((now % 450) / CaptionRenderer.ENTRANCE_MS.toFloat()).coerceIn(0f, 1f)

        val frame = Bitmap.createBitmap(vw, vh, Bitmap.Config.ARGB_8888)
        val c = Canvas(frame)
        bg.shader = LinearGradient(0f, 0f, vw.toFloat(), vh.toFloat(), Color.rgb(64, 54, 44), Color.rgb(18, 22, 34), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, vw.toFloat(), vh.toFloat(), bg)
        // Where apps put their own buttons and text, roughly.
        if (!wide) {
            for (i in 0 until 4) c.drawCircle(vw * 0.92f, vh * (0.55f + i * 0.09f), vw * 0.035f, icon)
            c.drawRect(vw * 0.04f, vh * 0.86f, vw * 0.7f, vh * 0.94f, icon)
        }
        val safeW = vw * if (wide) CaptionRenderer.SAFE_WIDTH_WIDE else CaptionRenderer.SAFE_WIDTH_VERTICAL
        c.drawRect((vw - safeW) / 2f, vh * 0.14f, (vw + safeW) / 2f, vh * 0.8f, guide)

        val caption = renderer.render(line, active, phase, now, vw, vh)
        val centerY = (1f - renderer.anchorY(!wide)) / 2f * vh
        c.drawBitmap(caption, (vw - caption.width) / 2f, centerY - caption.height / 2f, null)
        caption.recycle()
        val old = (preview.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        preview.setImageBitmap(frame)
        old?.recycle()
        preview.postOnAnimationDelayed({ tick() }, 33)
    }

    // endregion

    // region Controls

    private fun update(new: CaptionStyle) {
        style = new
        renderer = makeRenderer()
        buildControls()
    }

    private fun flow() = ChipGroup(context).apply {
        chipSpacingHorizontal = dp(8f)
        chipSpacingVertical = dp(8f)
    }

    private fun section(title: String) = controls.addView(Glass.section(context, title))

    private fun <T> pills(options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
        val group = flow()
        options.forEach { o -> group.addView(Glass.pill(context, label(o), o == selected) { onPick(o) }) }
        controls.addView(group)
    }

    private fun toggle(title: String, value: Boolean, onChange: (Boolean) -> Unit) {
        controls.addView(MaterialSwitch(context).apply {
            text = title
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            isChecked = value
            minHeight = dp(52f)
            thumbTintList = null
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ContextCompat.getColor(context, R.color.accent), 0x33FFFFFF),
            )
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
    }

    private fun slider(from: Float, to: Float, step: Float, value: Float, label: (Float) -> String, onChange: (Float) -> Unit) {
        controls.addView(Slider(context).apply {
            valueFrom = from
            valueTo = to
            stepSize = step
            this.value = value.coerceIn(from, to)
            setLabelFormatter { label(it) }
            trackActiveTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
            // Apply when the finger lifts, so the controls aren't rebuilt mid-drag.
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) = Unit
                override fun onStopTrackingTouch(slider: Slider) = onChange(slider.value)
            })
        })
    }

    /** A round swatch that opens the colour picker. */
    private fun colorRow(items: List<Triple<String, Int, (Int) -> Unit>>) {
        val group = flow()
        items.forEach { (label, color, set) ->
            group.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_pill)
                setPadding(dp(10f), dp(6f), dp(14f), dp(6f))
                addView(View(context).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(dp(1f), Color.argb(120, 255, 255, 255))
                    }
                }, LinearLayout.LayoutParams(dp(22f), dp(22f)))
                addView(Glass.label(context, label).apply { setPadding(dp(8f), 0, 0, 0) })
                isClickable = true
                setOnClickListener { ColorPicker.show(context, label, color, set) }
            })
        }
        controls.addView(group)
    }

    private fun buildControls() {
        controls.removeAllViews()
        val s = style

        section("Presets")
        val presets = flow()
        CaptionStyle.PRESETS.forEach { (name, preset) ->
            presets.addView(Glass.pill(context, name) { update(preset.copy(font = preset.font)) })
        }
        controls.addView(presets)

        section("Font")
        val fonts = flow()
        CaptionFonts.all(context).forEach { font ->
            fonts.addView(Glass.pill(context, font.name, font.id == s.font) { update(style.copy(font = font.id)) }.apply {
                typeface = CaptionFonts.typeface(context, font.id)
                if (font.id.startsWith("file:")) {
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("Remove ${font.name}?")
                            .setPositiveButton(R.string.delete) { _, _ ->
                                CaptionFonts.delete(context, font.id)
                                update(if (style.font == font.id) style.copy(font = CaptionStyle().font) else style)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                        true
                    }
                }
            })
        }
        fonts.addView(Glass.pill(context, "+ Import font") { onImportFont() })
        controls.addView(fonts)
        controls.addView(Glass.caption(context, "Import any .ttf or .otf font (long-press an imported font to remove it).").apply {
            setPadding(0, dp(6f), 0, 0)
        })

        section("Size")
        slider(60f, 200f, 5f, s.sizePct.toFloat(), { "${it.toInt()}%" }) { update(style.copy(sizePct = it.toInt())) }
        section("Words on screen")
        pills(listOf(0, 1, 2, 3, 4), s.wordsPerLine, { if (it == 0) "Auto" else "$it" }) { update(style.copy(wordsPerLine = it)) }

        section("Colours")
        colorRow(
            listOf(
                Triple("Text", s.textColor) { c: Int -> update(style.copy(textColor = c)) },
                Triple("Highlight", s.highlightColor) { c: Int -> update(style.copy(highlightColor = c)) },
                Triple("Outline", s.outlineColor) { c: Int -> update(style.copy(outlineColor = c)) },
                Triple("Shadow", s.shadowColor) { c: Int -> update(style.copy(shadowColor = c)) },
                Triple("Background", s.backgroundColor) { c: Int -> update(style.copy(backgroundColor = c)) },
            )
        )

        section("Spoken word")
        pills(CaptionHighlight.entries, s.highlight, { it.label }) { update(style.copy(highlight = it)) }
        section("Animation")
        pills(CaptionAnimation.entries, s.animation, { it.label }) { update(style.copy(animation = it)) }
        section("Letters")
        pills(CaptionEffect.entries, s.effect, { it.label }) { update(style.copy(effect = it)) }
        section("Outline")
        pills(listOf(0, 1, 2, 3, 4), s.outline, { if (it == 0) "Off" else "$it" }) { update(style.copy(outline = it)) }
        section("Shadow")
        pills(CaptionShadow.entries, s.shadow, { it.label }) { update(style.copy(shadow = it)) }
        section("Background")
        pills(CaptionBackground.entries, s.background, { it.label }) { update(style.copy(background = it)) }
        section("Position")
        pills(CaptionPosition.entries, s.position, { it.label }) { update(style.copy(position = it)) }
        controls.addView(Glass.caption(
            context,
            "Auto sits a little below the middle (about 60% down in tall videos), inside the safe area.",
        ).apply { setPadding(0, dp(6f), 0, 0) })

        section("Auto emphasis")
        toggle("Emphasise key words automatically", s.emphasis) { update(style.copy(emphasis = it)) }
        if (s.emphasis) {
            colorRow(listOf(Triple("Key word", s.emphasisColor) { c: Int -> update(style.copy(emphasisColor = c)) }))
            controls.addView(Glass.label(context, "Key word size").apply { setPadding(0, dp(10f), 0, 0) })
            slider(80f, 220f, 5f, s.emphasisSizePct.toFloat(), { "${it.toInt()}%" }) { update(style.copy(emphasisSizePct = it.toInt())) }
            controls.addView(Glass.label(context, "Key word effect").apply { setPadding(0, dp(6f), 0, dp(6f)) })
            pills(WordEffect.entries, s.emphasisEffect, { it.label }) { update(style.copy(emphasisEffect = it)) }
            controls.addView(android.widget.EditText(context).apply {
                setText(s.emphasisWords)
                hint = "Your key words, comma separated (e.g. free, secret, bhai)"
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10f) }
                // Typing doesn't rebuild the controls (that would drop the keyboard); the preview follows.
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun beforeTextChanged(t: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(t: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(t: android.text.Editable?) {
                        style = style.copy(emphasisWords = t?.toString().orEmpty())
                        renderer = makeRenderer()
                    }
                })
            })
            controls.addView(Glass.caption(
                context,
                "Numbers, money (₹, $), and words with an emoji match are always key words.",
            ).apply { setPadding(0, dp(6f), 0, 0) })
        }

        section("Extras")
        toggle("Glow on the spoken word", s.glow) { update(style.copy(glow = it)) }
        toggle("Karaoke: words appear as they're said", s.reveal) { update(style.copy(reveal = it)) }
        toggle("UPPERCASE", s.uppercase) { update(style.copy(uppercase = it)) }
        toggle("Auto emoji (💰 🔥 ❤️ after matching words)", s.autoEmoji) { update(style.copy(autoEmoji = it)) }
    }

    // endregion

}
