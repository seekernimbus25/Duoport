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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A floating, see-through script that scrolls by itself while you talk to the camera.
 * Drag it from anywhere on it; it sits under the front camera by default for eye contact.
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

    /** A shot's own lines (shot list), shown instead of your script while that shot is up. */
    private var shotScript: String? = null
    private var onShotScriptEdited: ((String) -> Unit)? = null

    /** Shows [lines] for the current shot (null = back to your own script); edits go to [onEdited]. */
    fun useShotScript(lines: String?, onEdited: ((String) -> Unit)?) {
        shotScript = lines?.takeIf { it.isNotBlank() }
        onShotScriptEdited = if (shotScript != null) onEdited else null
        text.text = shotScript ?: script
        restart()
    }

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
    /** Recent sound levels (time, level): the room's noise and whether speech is going on. */
    private val levels = ArrayDeque<Pair<Long, Double>>()
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

        // The buttons wrap onto a second row when the box is narrow (eye contact, sideways);
        // a sideways-scrolling bar would fight with dragging the box.
        val bar = com.google.android.material.chip.ChipGroup(context).apply {
            chipSpacingHorizontal = 0
            chipSpacingVertical = 0
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
        bar.addView(handle)
        bar.addView(playButton)
        bar.addView(smallButton("−") { setSpeed(speed - speedStep(speed - 1f)) }.apply { contentDescription = "Slower" })
        bar.addView(speedLabel)
        bar.addView(smallButton("+") { setSpeed(speed + speedStep(speed)) }.apply { contentDescription = "Faster" })
        bar.addView(smallButton("A−") { setTextSize(textSizeSp - 2f) }.apply { contentDescription = "Smaller text" })
        bar.addView(smallButton("A+") { setTextSize(textSizeSp + 2f) }.apply { contentDescription = "Bigger text" })
        bar.addView(smallButton("✎") { editScript() }.apply { contentDescription = "Edit the script" })
        bar.addView(smallButton("↺") { restart() }.apply { contentDescription = "Back to the start" })
        eyeButton = smallButton("◎") { setEyeContact(!eyeContact) }.also {
            it.contentDescription = "Eye contact: narrow column under the camera"
            bar.addView(it)
        }
        voiceButton = smallButton("🎙") { setVoiceFollow(!voiceFollow) }.also {
            it.contentDescription = "Follow my voice"
            bar.addView(it)
        }
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
        eyeContact = on
        prefs.edit().putBoolean("eyeContact", on).apply()
        eyeButton?.setTextColor(if (on) Color.rgb(216, 185, 138) else Color.WHITE)
        place()
    }

    /** Quarter-turn so the script reads upright with the phone held sideways: 0, 90 or -90. */
    var turn = 0f
        private set

    /**
     * The camera screen stays portrait (turning it would restart the camera), so with the phone
     * sideways only the teleprompter turns to read upright. Sideways, the front lens sits in the
     * middle of your left or right edge, so the script goes right there, centred on the lens,
     * with short lines (shorter still with eye contact) so your eyes stay by the camera. Drag
     * the handle to put it anywhere else.
     */
    fun turnTo(degrees: Float) {
        if (degrees == turn) return
        turn = degrees
        place()
    }

    private fun place() {
        val parentView = parent as? View ?: return
        if (parentView.width == 0) {
            post { place() }
            return
        }
        // The box as the layout has it, before any of this.
        val base = savedParams ?: FrameLayout.LayoutParams(layoutParams as? FrameLayout.LayoutParams ?: return)
            .also { savedParams = it }
        val lp = FrameLayout.LayoutParams(base)
        val w = parentView.width
        val h = parentView.height
        if (turn == 0f) {
            if (eyeContact) {
                lp.width = (w * 0.5f).toInt().coerceAtLeast((240 * density).toInt())
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.topMargin = topInset + (4 * density).toInt()
                lp.leftMargin = 0
                lp.rightMargin = 0
            }
            rotation = 0f
            translationX = 0f
        } else {
            // Line length runs along the phone's long side; the box is laid out unturned and
            // turned about its centre, so its centre is placed where the turned box should sit.
            val long = (h * if (eyeContact) 0.32f else 0.45f).toInt()
            val depth = minOf((base.height * 1.2f).toInt(), (w * 0.8f).toInt())
            lp.width = long
            lp.height = depth
            lp.gravity = Gravity.CENTER
            lp.setMargins(0, 0, 0, 0)
            // Its end touches the lens end of the screen (the portrait top, under the status bar)
            // and it's centred across, which is the lens's line.
            // (FrameLayout moves a centred child up by the whole bottom margin.)
            lp.bottomMargin = (h / 2 - long / 2 - topInset - (8 * density).toInt()).coerceAtLeast(0)
            rotation = turn
            translationX = 0f
        }
        text.gravity = if (eyeContact) Gravity.CENTER else Gravity.CENTER_HORIZONTAL
        translationY = 0f
        layoutParams = lp
    }

    private fun setVoiceFollow(on: Boolean) {
        voiceFollow = on
        prefs.edit().putBoolean("voiceFollow", on).apply()
        voiceButton?.setTextColor(if (on) Color.rgb(216, 185, 138) else Color.WHITE)
    }

    /**
     * The microphone level while recording (0..1). Speech is sound well above the room's
     * background noise (4x a low percentile of the last few seconds, so a fan doesn't count)
     * that keeps going: a third of the last moment is loud. A breath, a click or a creak is a
     * blip and doesn't move the script. Tuned on a phone take with a fan on: the script moved
     * in 4% of a 10 s silence (was 44%) and kept up with reading as before.
     */
    fun onVoiceLevel(amplitude: Double) {
        val now = System.nanoTime()
        lastLevelAt = now
        levels.addLast(now to amplitude)
        while (levels.first().first < now - NOISE_WINDOW_NANOS) levels.removeFirst()
        val sorted = levels.map { it.second }.sorted()
        val noiseFloor = sorted[sorted.size / 10].coerceAtMost(0.05)
        val threshold = maxOf(0.03, noiseFloor * 4)
        val recent = levels.filter { it.first >= now - SPEECH_WINDOW_NANOS }
        val loud = recent.count { it.second > threshold }
        val speaking = if (recent.size >= 2) {
            loud >= 2 && loud * 3 >= recent.size
        } else {
            // Levels arriving too rarely to see a stretch: only a clearly loud one counts.
            amplitude > threshold * 2
        }
        if (speaking) lastSpeechAt = now
    }

    /**
     * How fast to scroll right now: full speed while talking, stopping quickly in pauses so the
     * next line is still there when you start again. Gaps between words are shorter than
     * [SPEECH_HOLD_NANOS], so it doesn't stutter mid-sentence.
     */
    private fun currentPace(now: Long): Float {
        val listening = voiceFollow && now - lastLevelAt < 1_000_000_000L
        val target = if (!listening || now - lastSpeechAt < SPEECH_HOLD_NANOS) 1f else 0f
        pace += (target - pace) * 0.3f
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

    /** Fine steps at reading speeds, bigger ones above: 2 below 40, 5 below 80, then 10. */
    private fun speedStep(at: Float) = when {
        at < 40f -> 2f
        at < 80f -> 5f
        else -> 10f
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
            setText(shotScript ?: script)
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
                val edited = input.text.toString()
                if (shotScript != null) {
                    shotScript = edited
                    onShotScriptEdited?.invoke(edited)
                    text.text = edited
                } else {
                    script = edited
                    text.text = script
                    prefs.edit().putString("script", script).apply()
                }
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
    // Dragging: a finger that moves past the touch slop anywhere on the box moves the box (the
    // buttons and the script then get a cancel); a tap still reaches them.
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var startTx = 0f
    private var startTy = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> startDrag(e)
            MotionEvent.ACTION_MOVE -> if (!dragging && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > touchSlop) dragging = true
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // Down on the box itself (not a button): keep the gesture so it can become a drag.
            MotionEvent.ACTION_DOWN -> startDrag(e)
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && kotlin.math.hypot(e.rawX - downX, e.rawY - downY) > touchSlop) dragging = true
                if (dragging) dragTo(e)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    private fun startDrag(e: MotionEvent) {
        downX = e.rawX
        downY = e.rawY
        startTx = translationX
        startTy = translationY
        dragging = false
        // The box's own check above runs before the script's scroll view sees the move, so the
        // drag is the box's; nothing above the box should take it either.
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun dragTo(e: MotionEvent) {
        val parent = parent as? View
        var tx = startTx + e.rawX - downX
        var ty = startTy + e.rawY - downY
        if (parent != null) {
            // Keep what you see on screen; turned sideways, the box's on-screen width and height
            // are swapped.
            val turned = rotation % 180f != 0f
            val halfW = (if (turned) height else width) / 2f
            val halfH = (if (turned) width else height) / 2f
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            if (halfW * 2 <= parent.width) tx = tx.coerceIn(halfW - cx, parent.width - halfW - cx)
            if (halfH * 2 <= parent.height) ty = ty.coerceIn(halfH - cy, parent.height - halfH - cy)
        }
        translationX = tx
        translationY = ty
    }

    override fun onDetachedFromWindow() {
        pause()
        super.onDetachedFromWindow()
    }

    private companion object {
        /** How long after the last sound the script keeps moving. */
        const val SPEECH_HOLD_NANOS = 350_000_000L

        /** How much recent sound the room's noise level is taken from. */
        const val NOISE_WINDOW_NANOS = 8_000_000_000L

        /** Speech has to fill most of this last moment to count. */
        const val SPEECH_WINDOW_NANOS = 300_000_000L

        const val DEFAULT_SCRIPT =
            "Tap ✎ to paste your script.\n\nTap ▶ to start scrolling, or just press record: " +
                "the script starts by itself.\n\nUse − and + to change the speed, A− and A+ for text size, " +
                "and drag the top bar to move this box under your camera.\n\nWith 🎙 on, the script follows your " +
                "voice while recording: it moves while you talk and waits when you pause."
    }
}
