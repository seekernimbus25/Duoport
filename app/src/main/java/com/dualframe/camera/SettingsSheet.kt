package com.dualframe.camera

import android.content.Context
import android.content.res.ColorStateList
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * The settings sheet: which videos to make, how they're encoded, how the camera records.
 * Pro-only picture controls (profile, LUT, shutter…) live on the Pro screen instead.
 * Calls [onDone] with the edited settings when the sheet closes.
 */
class SettingsSheet(
    private val context: Context,
    initial: CaptureSettings,
    private val inFrontAndBack: Boolean,
    private val onDone: (CaptureSettings) -> Unit,
) {
    private var draft = initial
    private val dialog = BottomSheetDialog(context)
    private val root = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(24f)
        setPadding(p, dp(8f), p, dp(40f))
    }

    private fun dp(v: Float) = Glass.dp(context, v).toInt()

    fun show() {
        root.addView(Glass.body(context, context.getString(R.string.settings)).apply {
            setTextAppearance(R.style.DF_Text_Title)
            setPadding(0, dp(8f), 0, 0)
        })

        formats()
        export()
        camera()
        extras()

        dialog.setContentView(NestedScrollView(context).apply { addView(root) })
        dialog.setOnDismissListener { onDone(draft) }
        dialog.behavior.peekHeight = (context.resources.displayMetrics.heightPixels * 0.72f).toInt()
        Glass.blurBehind(dialog)
        dialog.show()
    }

    private fun flow() = ChipGroup(context).apply {
        chipSpacingHorizontal = dp(8f)
        chipSpacingVertical = dp(8f)
    }

    /** A row of pills where exactly one is selected. */
    private fun <T> choices(
        options: List<T>,
        selected: T,
        label: (T) -> String,
        enabled: (T) -> Boolean = { true },
        onPick: (T) -> Unit,
    ): ChipGroup {
        val group = flow()
        options.forEach { option ->
            group.addView(Glass.pill(context, label(option), option == selected).apply {
                isEnabled = enabled(option)
                setOnClickListener {
                    for (i in 0 until group.childCount) group.getChildAt(i).isSelected = false
                    isSelected = true
                    onPick(option)
                }
            })
        }
        root.addView(group)
        return group
    }

    private fun toggle(title: String, subtitle: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        root.addView(MaterialSwitch(context).apply {
            text = if (subtitle == null) title else "$title\n$subtitle"
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            textSize = 15f
            isChecked = value
            minHeight = dp(56f)
            thumbTintList = null
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(ContextCompat.getColor(context, R.color.accent), 0x33FFFFFF),
            )
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        })
    }

    private fun formats() {
        root.addView(Glass.section(context, "Videos to make"))
        root.addView(Glass.caption(context, "Each shape you turn on becomes its own video from the same recording."))
        OutputFormat.entries.forEach { format ->
            var resolution = draft.outputs[format] ?: OutputResolution.FHD
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
                setPadding(dp(16f), dp(6f), dp(16f), dp(12f))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(10f) }
            }
            val resolutions = flow()
            OutputResolution.entries.forEach { res ->
                val (w, h) = format.sizePx(res)
                resolutions.addView(Glass.pill(context, res.label, res == resolution).apply {
                    contentDescription = "${res.label}, $w by $h"
                    setOnClickListener {
                        for (i in 0 until resolutions.childCount) resolutions.getChildAt(i).isSelected = false
                        isSelected = true
                        resolution = res
                        if (format in draft.outputs) draft = draft.copy(outputs = draft.outputs + (format to res))
                    }
                })
            }
            val switch = MaterialSwitch(context).apply {
                text = "${format.label}   ·   ${format.use}"
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 16f
                minHeight = dp(52f)
                isChecked = format in draft.outputs
                thumbTintList = null
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(format.color, 0x33FFFFFF),
                )
            }
            resolutions.isVisible = switch.isChecked
            switch.setOnCheckedChangeListener { box, checked ->
                if (!checked && draft.outputs.keys == setOf(format)) {
                    box.isChecked = true
                    Toast.makeText(context, R.string.keep_one_format, Toast.LENGTH_SHORT).show()
                    return@setOnCheckedChangeListener
                }
                draft = draft.copy(outputs = if (checked) draft.outputs + (format to resolution) else draft.outputs - format)
                resolutions.visibility = if (checked) View.VISIBLE else View.GONE
            }
            card.addView(switch)
            card.addView(resolutions)
            root.addView(card)
        }
    }

    private fun export() {
        root.addView(Glass.section(context, "Video quality"))
        choices(ExportQuality.entries, draft.exportQuality, { it.label }) { draft = draft.copy(exportQuality = it) }
        root.addView(Glass.caption(context, "Higher keeps more detail and makes bigger files.").apply { setPadding(0, dp(6f), 0, 0) })
        root.addView(Glass.section(context, "Codec"))
        choices(VideoCodec.entries, draft.codec, { it.label }) { draft = draft.copy(codec = it) }
        root.addView(Glass.caption(context, "H.264 plays everywhere. HEVC is about half the size at the same quality.").apply {
            setPadding(0, dp(6f), 0, 0)
        })
    }

    private fun camera() {
        root.addView(Glass.section(context, "Camera recording"))
        if (inFrontAndBack) {
            root.addView(Glass.caption(context, "Front + Back always records the full 4:3 sensor at up to 1080p."))
        }
        val frameNote = Glass.caption(context, draft.cameraFrame.description)
        choices(CameraFrame.entries, draft.cameraFrame, { it.label }) {
            draft = draft.copy(cameraFrame = it)
            frameNote.text = it.description
        }
        root.addView(frameNote.apply { setPadding(0, dp(6f), 0, dp(10f)) })
        choices(CameraResolution.entries, draft.cameraResolution, { it.label }) {
            draft = draft.copy(cameraResolution = it)
        }
        root.addView(Glass.section(context, "Camera bitrate"))
        choices(RecordBitrate.entries, draft.recordBitrate, { it.label }) { draft = draft.copy(recordBitrate = it) }
        root.addView(Glass.caption(context, "Data rate of the original recording. Higher survives grading better.").apply {
            setPadding(0, dp(6f), 0, 0)
        })

        root.addView(Glass.section(context, "Stabilization"))
        val stabNote = Glass.caption(context, draft.stabilization.description)
        choices(Stabilization.entries, draft.stabilization, { it.label }) {
            draft = draft.copy(stabilization = it)
            stabNote.text = it.description
        }
        root.addView(stabNote.apply { setPadding(0, dp(6f), 0, 0) })

        root.addView(Glass.section(context, "Speed"))
        val speedNote = Glass.caption(context, "")
        val factors = flow()
        fun describe() {
            speedNote.text = when {
                draft.timelapse == 1 -> "Videos play back in real time."
                draft.hyperlapse -> "Hyperlapse: walk, drive or pan while recording. Strongest stabilization is used and " +
                    "the videos play ${draft.timelapse}× faster without sound. The original stays real-time."
                else -> "Time-lapse: keep the phone still (a tripod helps). The videos play ${draft.timelapse}× faster " +
                    "without sound. The original stays real-time."
            }
            factors.visibility = if (draft.timelapse == 1) View.GONE else View.VISIBLE
        }
        val kinds = listOf("Normal", "Time-lapse", "Hyperlapse")
        val kind = when {
            draft.timelapse == 1 -> "Normal"
            draft.hyperlapse -> "Hyperlapse"
            else -> "Time-lapse"
        }
        choices(kinds, kind, { it }) {
            draft = when (it) {
                "Normal" -> draft.copy(timelapse = 1, hyperlapse = false)
                "Hyperlapse" -> draft.copy(timelapse = draft.timelapse.takeIf { t -> t > 1 } ?: 10, hyperlapse = true)
                else -> draft.copy(timelapse = draft.timelapse.takeIf { t -> t > 1 } ?: 10, hyperlapse = false)
            }
            for (i in 0 until factors.childCount) {
                factors.getChildAt(i).isSelected = (factors.getChildAt(i).tag as Int) == draft.timelapse
            }
            describe()
        }
        listOf(5, 10, 15, 30).forEach { f ->
            factors.addView(Glass.pill(context, "${f}×", f == draft.timelapse).apply {
                tag = f
                setOnClickListener {
                    for (i in 0 until factors.childCount) factors.getChildAt(i).isSelected = false
                    isSelected = true
                    draft = draft.copy(timelapse = f)
                    describe()
                }
            })
        }
        factors.setPadding(0, dp(8f), 0, 0)
        root.addView(factors)
        root.addView(speedNote.apply { setPadding(0, dp(6f), 0, 0) })
        describe()
    }

    private fun extras() {
        root.addView(Glass.section(context, "Clip name"))
        val name = android.widget.EditText(context).apply {
            setText(draft.clipName)
            setSingleLine()
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
            hint = "DualFrame"
            background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
            setPadding(dp(16f), dp(12f), dp(16f), dp(12f))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    draft = draft.copy(clipName = s?.toString()?.trim().orEmpty().ifEmpty { "DualFrame" })
                }
            })
        }
        root.addView(name)
        root.addView(Glass.caption(context, "Files are named like a slate: name, take number, format.").apply {
            setPadding(0, dp(6f), 0, 0)
        })

        root.addView(Glass.section(context, "Extras"))
        toggle("Volume buttons record", "Press either volume button to start or stop", draft.volumeKeysRecord) {
            draft = draft.copy(volumeKeysRecord = it)
        }
        toggle("Mirror the front camera", "Record selfies the way the preview shows them", draft.mirrorFront) {
            draft = draft.copy(mirrorFront = it)
        }
        toggle("Grid lines", null, draft.grid) { draft = draft.copy(grid = it) }
        toggle("Keep the original recording", "Also saves the uncut file (always useful with Log or HDR)", draft.keepOriginal) {
            draft = draft.copy(keepOriginal = it)
        }
    }
}
