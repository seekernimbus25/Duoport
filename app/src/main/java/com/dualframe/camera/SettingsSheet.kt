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
    /** Slow-motion frame rates the current camera can record. */
    private val slowRates: List<Int>,
    /** What this phone's cameras can do, as label / value rows. */
    private val phoneInfo: List<Pair<String, String>>,
    /** Highest bitrate this phone's video encoder accepts, in Mbps (0 = unknown). */
    private val encoderMaxMbps: Int,
    /** Which microphone recording will use right now ("USB mic"…). */
    private val micName: String,
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
        captions()
        sound()
        extras()
        phone()

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
        val rates = RecordBitrate.CHOICES.filter { it == 0 || encoderMaxMbps <= 0 || it <= encoderMaxMbps }
        choices(rates, draft.recordMbps.takeIf { it in rates } ?: 0, { RecordBitrate.label(it) }) {
            draft = draft.copy(recordMbps = it)
        }
        root.addView(Glass.caption(
            context,
            "Data rate of the camera recording itself. Higher keeps more detail for grading and makes bigger files." +
                if (encoderMaxMbps > 0) " This phone's encoder goes up to $encoderMaxMbps Mbps." else "",
        ).apply { setPadding(0, dp(6f), 0, 0) })

        root.addView(Glass.section(context, "Anamorphic lens"))
        val squeezes = listOf(1f, 1.33f, 1.5f, 1.55f, 1.8f, 2f)
        choices(squeezes, squeezes.minByOrNull { kotlin.math.abs(it - draft.anamorphic) } ?: 1f, {
            if (it == 1f) "Off" else "${it}×"
        }) { draft = draft.copy(anamorphic = it) }
        root.addView(Glass.caption(
            context,
            "For clip-on anamorphic lenses. Pick the lens's squeeze: the frames show the real (desqueezed) shapes " +
                "and your videos and photos come out desqueezed. Mount the lens for sideways shooting and turn the phone." +
                if (inFrontAndBack) " Not used in Front + Back." else "",
        ).apply { setPadding(0, dp(6f), 0, 0) })

        root.addView(Glass.section(context, "Cinematic mode"))
        choices(listOf(0, 1, 2, 3), draft.cinematic, { listOf("Off", "Subtle", "Medium", "Strong")[it] }) {
            draft = draft.copy(cinematic = it)
        }
        root.addView(Glass.caption(
            context,
            "Blurs the background behind people, like a big-sensor camera with a fast lens. Phones don't share depth " +
                "with apps, so an on-device model finds the people: it works on people, not objects or pets. " +
                "You see it live while you shoot; it's added to your videos and photos when they're made, " +
                "so making them takes longer.",
        ).apply { setPadding(0, dp(6f), 0, 0) })

        root.addView(Glass.section(context, "Stabilization"))
        val stabNote = Glass.caption(context, draft.stabilization.description)
        choices(Stabilization.entries, draft.stabilization, { it.label }) {
            draft = draft.copy(stabilization = it)
            stabNote.text = it.description
        }
        root.addView(stabNote.apply { setPadding(0, dp(6f), 0, 0) })

        speed()
    }

    private enum class SpeedKind(val label: String) { NORMAL("Normal"), SLOW("Slow motion"), TIMELAPSE("Time-lapse"), HYPERLAPSE("Hyperlapse") }

    private fun speedKind() = when {
        draft.slowMotion > 0 -> SpeedKind.SLOW
        draft.timelapse == 1 -> SpeedKind.NORMAL
        draft.hyperlapse -> SpeedKind.HYPERLAPSE
        else -> SpeedKind.TIMELAPSE
    }

    private fun speed() {
        root.addView(Glass.section(context, "Speed"))
        val note = Glass.caption(context, "")
        val lapseFactors = flow()
        val slowFactors = flow()
        fun describe() {
            note.text = when (speedKind()) {
                SpeedKind.NORMAL -> "Videos play back in real time."
                SpeedKind.SLOW -> "Slow motion: the camera records ${draft.slowMotion} fps and your videos play " +
                    "${draft.slowMotion / 30}× slower at 30 fps, without sound." +
                    if (draft.slowMotion >= 120) " Needs good light; manual controls pause at this speed." else ""
                SpeedKind.HYPERLAPSE -> "Hyperlapse: walk, drive or pan while recording. Strongest stabilization is used and " +
                    "the videos play ${draft.timelapse}× faster without sound. The original stays real-time."
                SpeedKind.TIMELAPSE -> "Time-lapse: keep the phone still (a tripod helps). The videos play ${draft.timelapse}× " +
                    "faster without sound. The original stays real-time."
            }
            lapseFactors.visibility = if (draft.timelapse > 1 && draft.slowMotion == 0) View.VISIBLE else View.GONE
            slowFactors.visibility = if (draft.slowMotion > 0) View.VISIBLE else View.GONE
            for (i in 0 until lapseFactors.childCount) {
                lapseFactors.getChildAt(i).isSelected = lapseFactors.getChildAt(i).tag == draft.timelapse
            }
            for (i in 0 until slowFactors.childCount) {
                slowFactors.getChildAt(i).isSelected = slowFactors.getChildAt(i).tag == draft.slowMotion
            }
        }
        val slowOk = slowRates.isNotEmpty() && !inFrontAndBack
        choices(SpeedKind.entries, speedKind(), { it.label }, enabled = { it != SpeedKind.SLOW || slowOk }) {
            val lapse = draft.timelapse.takeIf { t -> t > 1 } ?: 10
            draft = when (it) {
                SpeedKind.NORMAL -> draft.copy(timelapse = 1, hyperlapse = false, slowMotion = 0)
                SpeedKind.SLOW -> draft.copy(
                    timelapse = 1,
                    hyperlapse = false,
                    slowMotion = draft.slowMotion.takeIf { r -> r in slowRates } ?: slowRates.max(),
                )
                SpeedKind.HYPERLAPSE -> draft.copy(timelapse = lapse, hyperlapse = true, slowMotion = 0)
                SpeedKind.TIMELAPSE -> draft.copy(timelapse = lapse, hyperlapse = false, slowMotion = 0)
            }
            describe()
        }
        listOf(5, 10, 15, 30).forEach { f ->
            lapseFactors.addView(Glass.pill(context, "${f}×", f == draft.timelapse).apply {
                tag = f
                setOnClickListener {
                    draft = draft.copy(timelapse = f)
                    describe()
                }
            })
        }
        slowRates.forEach { fps ->
            slowFactors.addView(Glass.pill(context, "${fps / 30}×  ·  $fps fps", fps == draft.slowMotion).apply {
                tag = fps
                setOnClickListener {
                    draft = draft.copy(slowMotion = fps)
                    describe()
                }
            })
        }
        lapseFactors.setPadding(0, dp(8f), 0, 0)
        slowFactors.setPadding(0, dp(8f), 0, 0)
        root.addView(lapseFactors)
        root.addView(slowFactors)
        root.addView(note.apply { setPadding(0, dp(6f), 0, 0) })
        if (inFrontAndBack) {
            root.addView(Glass.caption(context, "Slow motion needs one camera, so it's off in Front + Back."))
        } else if (slowRates.isEmpty()) {
            root.addView(Glass.caption(context, "This camera doesn't offer slow motion to apps."))
        } else if (slowRates.none { it >= 120 }) {
            root.addView(Glass.caption(context, "This phone only gives apps 60 fps, so slow motion is 2×. 120 / 240 fps appear here on phones that open their high-speed mode to apps."))
        }
        describe()
    }

    private fun phone() {
        if (phoneInfo.isEmpty()) return
        root.addView(Glass.section(context, "This phone"))
        root.addView(Glass.caption(context, "The app checks what your phone's cameras allow and only offers what works on it."))
        phoneInfo.forEach { (label, value) ->
            root.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8f), 0, 0)
                addView(
                    Glass.body(context, label).apply { alpha = 0.7f },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                addView(
                    Glass.body(context, value).apply { gravity = android.view.Gravity.END },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f),
                )
            })
        }
    }

    private fun captions() {
        root.addView(Glass.section(context, "Captions"))
        val available = Transcriber.isAvailable(context)
        choices(CaptionLanguage.entries, draft.captions, { it.label }, enabled = { available || it == CaptionLanguage.OFF }) {
            draft = draft.copy(captions = it)
        }
        root.addView(Glass.caption(
            context,
            if (available) {
                "Word-by-word captions burned into your videos, plus an .srt file in Download/DualFrame for YouTube. " +
                    "Hinglish writes Hindi words in Roman letters. Free and private: Android's own speech engine " +
                    "runs on the phone, nothing is uploaded. Not added to time-lapse or slow motion."
            } else {
                "Captions need Android 13 or newer with on-device speech recognition, which this phone doesn't offer."
            },
        ).apply { setPadding(0, dp(6f), 0, 0) })
    }

    private fun sound() {
        root.addView(Glass.section(context, "Sound"))
        choices(listOf(-12, -6, -3, 0, 3, 6, 12), draft.audioGainDb, {
            if (it == 0) "0 dB" else if (it > 0) "+$it dB" else "$it dB"
        }) { draft = draft.copy(audioGainDb = it) }
        root.addView(Glass.caption(
            context,
            "Raises or lowers the level of your finished videos. Recording now uses: $micName. A USB-C microphone, " +
                "or an XLR mic through a USB audio interface, is used automatically when plugged in. Android doesn't " +
                "let apps set the mic's input gain, so set the level on the mic or interface itself.",
        ).apply { setPadding(0, dp(6f), 0, 0) })
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
        toggle(
            "Follow me (auto-reframe)",
            "Every frame follows the main face, live and in the finished videos",
            draft.autoReframe,
        ) { draft = draft.copy(autoReframe = it) }
        toggle(
            "Record sideways when the phone is turned",
            "Hold the phone landscape for a landscape recording (one camera only)",
            draft.followRotation,
        ) { draft = draft.copy(followRotation = it) }
        toggle("Keep the original recording", "Also saves the uncut file. Useful with Log or HLG for re-framing and re-grading", draft.keepOriginal) {
            draft = draft.copy(keepOriginal = it)
        }
    }
}
