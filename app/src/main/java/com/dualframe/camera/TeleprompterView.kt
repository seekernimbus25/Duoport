package com.dualframe.camera

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A floating, see-through script that scrolls by itself while you talk to the camera.
 * Drag it by its top bar; it sits under the front camera by default for eye contact.
 * The script, speed and text size are remembered.
 */
class TeleprompterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val prefs = context.getSharedPreferences("teleprompter", Context.MODE_PRIVATE)
    private val density = resources.displayMetrics.density

    /** Scroll speed in dp per second. */
    private var speed = prefs.getFloat("speed", 40f)
    private var textSizeSp = prefs.getFloat("textSize", 26f)
    var script: String = prefs.getString("script", null) ?: DEFAULT_SCRIPT
        private set

    var isScrolling = false
        private set

    /** Status bar height, so eye-contact mode can sit right under the front camera. */
    var topInset = 0

    /**
     * Eye-contact mode: a narrow column pinned right under the front camera. With only a few
     * words per line your eyes barely move while reading, so you still look into the lens.
     */
    private var eyeContact = prefs.getBoolean("eyeContact", false)
    private var savedParams: FrameLayout.LayoutParams? = null

    private val scroller = ScrollView(context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
    }
    private val text = TextView(context).apply {
        setTextColor(Color.rgb(244, 241, 234))
        setLineSpacing(0f, 1.2f)
        gravity = Gravity.CENTER_HORIZONTAL
        setShadowLayer(3f * density, 0f, 1f * density, Color.BLACK)
    }
    private val playButton = smallButton("▶") { if (isScrolling) pause() else play() }
    private var eyeButton: MaterialButton? = null
    private var voiceButton: MaterialButton? = null

    /**
     * Voice follow: while recording, the script only moves while you're talking and waits when
     * you pause. Driven by the recording's sound level ([onVoiceLevel]); before recording starts
     * it scrolls normally.
     */
    private var voiceFollow = prefs.getBoolean("voiceFollow", true)
    private var lastLevelAt = 0L
    private var lastSpeechAt = 0L
    private var noiseFloor = 0.01
    private var pace = 1f
    private val speedLabel = TextView(context).apply {
        setTextColor(Color.rgb(244, 241, 234))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minWidth = (44 * density).toInt()
    }

    init {
        orientation = VERTICAL
        background = GradientDrawable().apply {
            cornerRadius = 22f * density
            setColor(Color.argb(150, 14, 14, 18))
            setStroke((1 * density).toInt(), Color.argb(46, 255, 255, 255))
        }
        val pad = (8 * density).toInt()
        setPadding(pad, pad / 2, pad, pad)

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(context).apply {
            text = "⋮⋮"
            contentDescription = "Drag to move"
            setTextColor(Color.argb(200, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            minHeight = (44 * density).toInt()
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, 0, pad, 0)
        }
        enableDragging(handle)
        bar.addView(handle)
        bar.addView(playButton)
        bar.addView(smallButton("−") { setSpeed(speed - 10f) })
        bar.addView(speedLabel)
        bar.addView(smallButton("+") { setSpeed(speed + 10f) })
        bar.addView(smallButton("A−") { setTextSize(textSizeSp - 2f) })
        bar.addView(smallButton("A+") { setTextSize(textSizeSp + 2f) })
        bar.addView(smallButton("✎") { editScript() })
        bar.addView(smallButton("↺") { restart() })
        eyeButton = smallButton("◎") { setEyeContact(!eyeContact) }.also { bar.addView(it) }
        voiceButton = smallButton("🎙") { setVoiceFollow(!voiceFollow) }.also {
            it.contentDescription = "Follow my voice"
            bar.addView(it)
        }
        // Narrow (eye-contact) mode can't fit every button, so the bar scrolls sideways.
        val barScroller = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        addView(barScroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        scroller.addView(text, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroller, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        text.text = script
        setTextSize(textSizeSp)
        setSpeed(speed)
        setVoiceFollow(voiceFollow)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (eyeContact) post { setEyeContact(true) }
    }

    private fun setEyeContact(on: Boolean) {
        val parentView = parent as? View ?: return
        val lp = layoutParams as? FrameLayout.LayoutParams ?: return
        eyeContact = on
        prefs.edit().putBoolean("eyeContact", on).apply()
        eyeButton?.setTextColor(if (on) Color.rgb(216, 185, 138) else Color.WHITE)
        if (on) {
            if (savedParams == null) savedParams = FrameLayout.LayoutParams(lp)
            lp.width = (parentView.width * 0.5f).toInt().coerceAtLeast((240 * density).toInt())
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.topMargin = topInset + (4 * density).toInt()
            lp.leftMargin = 0
            lp.rightMargin = 0
            text.gravity = Gravity.CENTER
        } else {
            savedParams?.let {
                lp.width = it.width
                lp.gravity = it.gravity
                lp.topMargin = it.topMargin
                lp.leftMargin = it.leftMargin
                lp.rightMargin = it.rightMargin
            }
            text.gravity = Gravity.CENTER_HORIZONTAL
        }
        translationX = 0f
        translationY = 0f
        layoutParams = lp
    }

    private fun setVoiceFollow(on: Boolean) {
        voiceFollow = on
        prefs.edit().putBoolean("voiceFollow", on).apply()
        voiceButton?.setTextColor(if (on) Color.rgb(216, 185, 138) else Color.WHITE)
    }

    /**
     * The microphone level while recording (0..1). Speech is anything clearly above the
     * room's background noise, which is tracked slowly so a fan or traffic doesn't count.
     */
    fun onVoiceLevel(amplitude: Double) {
        val now = System.nanoTime()
        lastLevelAt = now
        noiseFloor = if (amplitude < noiseFloor) amplitude else noiseFloor + (amplitude - noiseFloor) * 0.002
        if (amplitude > maxOf(0.02, noiseFloor * 2.5)) lastSpeechAt = now
    }

    /** How fast to scroll right now: full speed while talking, easing to a stop in pauses. */
    private fun currentPace(now: Long): Float {
        val listening = voiceFollow && now - lastLevelAt < 1_000_000_000L
        val target = if (!listening || now - lastSpeechAt < 700_000_000L) 1f else 0f
        pace += (target - pace) * 0.12f
        return pace
    }

    fun play() {
        if (isScrolling) return
        isScrolling = true
        playButton.text = "❚❚"
        lastFrameNanos = 0L
        postOnAnimation(tick)
    }

    fun pause() {
        isScrolling = false
        playButton.text = "▶"
        removeCallbacks(tick)
    }

    fun restart() {
        scroller.scrollTo(0, 0)
        scrollRemainder = 0f
    }

    private var lastFrameNanos = 0L
    private var scrollRemainder = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (!isScrolling) return
            val now = System.nanoTime()
            if (lastFrameNanos != 0L) {
                scrollRemainder += speed * currentPace(now) * density * (now - lastFrameNanos) / 1e9f
                val step = scrollRemainder.toInt()
                if (step > 0) {
                    scroller.scrollBy(0, step)
                    scrollRemainder -= step
                }
            }
            lastFrameNanos = now
            val atEnd = !scroller.canScrollVertically(1)
            if (atEnd) pause() else postOnAnimation(this)
        }
    }

    private fun setSpeed(value: Float) {
        speed = value.coerceIn(10f, 200f)
        speedLabel.text = "${speed.toInt()}"
        prefs.edit().putFloat("speed", speed).apply()
    }

    private fun setTextSize(value: Float) {
        textSizeSp = value.coerceIn(14f, 56f)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSizeSp)
        prefs.edit().putFloat("textSize", textSizeSp).apply()
    }

    private fun editScript() {
        pause()
        val input = EditText(context).apply {
            setText(script)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        val container = FrameLayout(context).apply {
            val p = (20 * density).toInt()
            setPadding(p, p / 2, p, 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("Your script")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                script = input.text.toString()
                text.text = script
                prefs.edit().putString("script", script).apply()
                restart()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun smallButton(label: String, onClick: () -> Unit) =
        MaterialButton(context).apply {
            text = label
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setTextColor(Color.rgb(244, 241, 234))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = (44 * density).toInt()
            minimumWidth = (44 * density).toInt()
            minHeight = (44 * density).toInt()
            minimumHeight = (44 * density).toInt()
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun enableDragging(handle: View) {
        var startX = 0f
        var startY = 0f
        var startTx = 0f
        var startTy = 0f
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX
                    startY = e.rawY
                    startTx = translationX
                    startTy = translationY
                }
                MotionEvent.ACTION_MOVE -> {
                    val parent = parent as? View
                    var tx = startTx + e.rawX - startX
                    var ty = startTy + e.rawY - startY
                    if (parent != null) {
                        tx = tx.coerceIn(-left.toFloat(), (parent.width - right).toFloat())
                        ty = ty.coerceIn(-top.toFloat(), (parent.height - bottom).toFloat())
                    }
                    translationX = tx
                    translationY = ty
                }
            }
            true
        }
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEFAULT_SCRIPT =
            "Tap ✎ to paste your script.\n\nTap ▶ to start scrolling, or just press record: " +
                "the script starts by itself.\n\nUse − and + to change the speed, A− and A+ for text size, " +
                "and drag the top bar to move this box under your camera.\n\nWith 🎙 on, the script follows your " +
                "voice while recording: it moves while you talk and waits when you pause."
    }
}
