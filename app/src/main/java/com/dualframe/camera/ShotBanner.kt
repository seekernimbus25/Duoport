package com.dualframe.camera

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible

/**
 * The camera's shot-list card: which shot is up (number, size, what happens), which take is
 * next, and — right after a take — whether to keep it and move on or go again.
 */
class ShotBanner(context: Context) : LinearLayout(context) {
    var onPrev: () -> Unit = {}
    var onNext: () -> Unit = {}
    var onList: () -> Unit = {}
    var onExit: () -> Unit = {}
    /** Keep the take and move to the next shot. */
    var onGood: () -> Unit = {}
    /** Keep the take as good but shoot this shot again (choose between good takes later). */
    var onGoodAgain: () -> Unit = {}
    /** Mark the take no good and shoot it again. */
    var onRetake: () -> Unit = {}

    private fun dp(v: Float) = Glass.dp(context, v).toInt()

    private val heading = Glass.label(context, "").apply { setTextColor(ContextCompat.getColor(context, R.color.accent)) }
    private val takeLabel = Glass.caption(context, "")
    private val description = Glass.body(context, "").apply { maxLines = 3 }
    private val notes = Glass.caption(context, "").apply { maxLines = 2 }
    private val nav = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val rate = LinearLayout(context).apply { orientation = VERTICAL }
    private val rateButtons = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val rateLabel = Glass.caption(context, "")

    init {
        orientation = VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        isClickable = true
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(heading, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(takeLabel)
        })
        addView(description)
        addView(notes)
        fun button(text: String, onClick: () -> Unit) = Glass.pill(context, text) { onClick() }.apply {
            minHeight = dp(36f)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6f) }
        }
        rate.addView(rateLabel)
        rateButtons.addView(button(context.getString(R.string.shots_good_next)) { onGood() })
        rateButtons.addView(button(context.getString(R.string.shots_good_again)) { onGoodAgain() })
        rateButtons.addView(button(context.getString(R.string.shots_retake)) { onRetake() })
        rate.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(rateButtons)
        }, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4f) })
        addView(rate, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
        nav.addView(button("‹") { onPrev() })
        nav.addView(button(context.getString(R.string.shots_list)) { onList() })
        nav.addView(button("›") { onNext() })
        nav.addView(android.view.View(context), LayoutParams(0, 1, 1f))
        nav.addView(button(context.getString(R.string.shots_exit)) { onExit() })
        addView(nav, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8f) })
    }

    /** [justShot]: the take number just recorded, to ask about it; null otherwise. */
    fun bind(project: ShotProject, recording: Boolean, justShot: Int?) {
        val shot = project.currentShot
        if (shot == null) {
            heading.text = context.getString(R.string.shots_banner_empty)
            takeLabel.text = ""
            description.isVisible = false
            notes.isVisible = false
            rate.isVisible = false
            nav.isVisible = !recording
            return
        }
        val index = project.shots.indexOf(shot)
        heading.text = context.getString(R.string.shots_banner_heading, shot.number, index + 1, project.shots.size, shot.type.label.uppercase())
        val next = maxOf(shot.takesStarted, shot.takes.maxOfOrNull { it.number } ?: 0) + 1
        takeLabel.text = if (recording) {
            context.getString(R.string.shots_recording_take, next - 1)
        } else {
            context.getString(R.string.shots_next_take, next)
        }
        description.text = shot.description
        description.isVisible = shot.description.isNotBlank()
        notes.text = shot.notes
        notes.isVisible = shot.notes.isNotBlank()
        rate.isVisible = justShot != null && !recording
        rateLabel.text = context.getString(R.string.shots_how_was, justShot ?: 0)
        nav.isVisible = !recording
    }
}
