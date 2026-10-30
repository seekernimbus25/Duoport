package com.dualframe.camera

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shot lists: your sequence written as shots (number, shot size, what happens, any lines to
 * say). Shoot it shot by shot from the camera, then pick the best take of each shot here and
 * join them in order.
 */
class ShotListActivity : AppCompatActivity() {

    private var project: ShotProject? = null
    private lateinit var title: TextView
    private lateinit var headerAction: MaterialButton
    private lateinit var body: LinearLayout

    private fun dp(v: Float) = Glass.dp(this, v).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ContextCompat.getColor(context, R.color.ink))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(8f), dp(16f), dp(8f))
        }
        header.addView(ImageButton(this, null, 0, R.style.DF_IconButton).apply {
            setImageResource(R.drawable.ic_arrow_back)
            contentDescription = getString(R.string.back)
            setOnClickListener { onBack() }
        })
        title = Glass.body(this, "").apply {
            setTextAppearance(R.style.DF_Text_Title)
            setPadding(dp(8f), 0, dp(8f), 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headerAction = MaterialButton(this)
        header.addView(headerAction)
        root.addView(header)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(40f))
        }
        root.addView(NestedScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        project = intent.getStringExtra(EXTRA_PROJECT)?.let { ShotLists.load(this, it) }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = onBack()
        })
    }

    override fun onResume() {
        super.onResume()
        // Takes may have been added while we were away (shooting).
        project = project?.let { ShotLists.load(this, it.id) }
        render()
    }

    private fun onBack() {
        if (project != null && intent.getStringExtra(EXTRA_PROJECT) == null) {
            project = null
            render()
        } else {
            finish()
        }
    }

    private fun render() {
        body.removeAllViews()
        val p = project
        if (p == null) renderProjects() else renderProject(p)
    }

    // region Project list

    private fun renderProjects() {
        title.text = getString(R.string.shots_title)
        headerAction.text = getString(R.string.shots_new)
        headerAction.setOnClickListener { askName(getString(R.string.shots_new), "") { name -> open(ShotLists.create(this, name)) } }
        body.addView(Glass.caption(this, getString(R.string.shots_intro)).apply { setPadding(0, dp(4f), 0, dp(12f)) })
        val projects = ShotLists.all(this)
        if (projects.isEmpty()) {
            body.addView(Glass.body(this, getString(R.string.shots_empty)).apply { setPadding(0, dp(24f), 0, 0) })
        }
        projects.forEach { p ->
            val done = p.shots.count { it.pick() != null }
            body.addView(card().apply {
                addView(Glass.body(context, p.name).apply { setTextAppearance(R.style.DF_Text_Title) })
                addView(Glass.caption(context, getString(R.string.shots_summary, p.shots.size, done)))
                setOnClickListener { open(p) }
                setOnLongClickListener {
                    MaterialAlertDialogBuilder(context)
                        .setTitle(getString(R.string.shots_delete_list, p.name))
                        .setMessage(R.string.shots_delete_list_note)
                        .setPositiveButton(R.string.delete) { _, _ -> ShotLists.delete(context, p); render() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
    }

    private fun open(p: ShotProject) {
        project = p
        render()
    }

    // endregion

    // region One shot list

    private fun renderProject(p: ShotProject) {
        title.text = p.name
        title.setOnClickListener { askName(getString(R.string.shots_rename), p.name) { p.name = it; ShotLists.save(this, p); render() } }
        headerAction.text = getString(R.string.shots_shoot)
        headerAction.setOnClickListener {
            if (p.shots.isEmpty()) {
                Toast.makeText(this, R.string.shots_add_first, Toast.LENGTH_SHORT).show()
            } else {
                shoot(p, p.shots.indexOfFirst { !it.done }.takeIf { it >= 0 } ?: 0)
            }
        }
        body.addView(ChipGroup(this).apply {
            chipSpacingHorizontal = dp(8f)
            chipSpacingVertical = dp(8f)
            setPadding(0, dp(4f), 0, dp(8f))
            addView(Glass.pill(context, getString(R.string.shots_add)) { editShot(p, null) })
            addView(Glass.pill(context, getString(R.string.shots_paste)) { pasteList(p) })
            addView(Glass.pill(context, getString(R.string.shots_assemble)) { assemble(p) })
        })
        shapesRow(p)
        if (p.shots.isEmpty()) {
            body.addView(Glass.body(this, getString(R.string.shots_project_empty)).apply { setPadding(0, dp(16f), 0, 0) })
            return
        }
        val active = ShotLists.activeId(this) == p.id
        p.shots.forEachIndexed { index, shot -> body.addView(shotCard(p, index, shot, active && index == p.current)) }
        body.addView(Glass.caption(this, getString(R.string.shots_project_note)).apply { setPadding(0, dp(12f), 0, 0) })
    }

    /** The shapes every take of this list is shot in; the camera switches to them for this list. */
    private fun shapesRow(p: ShotProject) {
        body.addView(Glass.label(this, getString(R.string.shots_shapes)).apply { setPadding(0, dp(8f), 0, dp(4f)) })
        body.addView(ChipGroup(this).apply {
            chipSpacingHorizontal = dp(8f)
            chipSpacingVertical = dp(8f)
            OutputFormat.entries.forEach { format ->
                addView(Glass.pill(context, format.label, format in p.outputs) {
                    if (format in p.outputs) {
                        if (p.outputs.size > 1) p.outputs.remove(format)
                    } else {
                        p.outputs[format] = SettingsStore.load(context).outputs[format] ?: OutputResolution.FHD
                    }
                    // Keep them in the usual order.
                    val ordered = OutputFormat.entries.filter { it in p.outputs }.associateWith { p.outputs.getValue(it) }
                    p.outputs.clear()
                    p.outputs.putAll(ordered)
                    ShotLists.save(context, p)
                    render()
                })
            }
        })
        body.addView(Glass.caption(this, getString(R.string.shots_shapes_note)))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_glass_card)
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(10f)
        }
    }

    private fun shotCard(p: ShotProject, index: Int, shot: Shot, isCurrent: Boolean) = card().apply {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(Glass.label(context, "${getString(R.string.shots_shot)} ${shot.number} · ${shot.type.label.uppercase()}").apply {
            setTextColor(ContextCompat.getColor(context, R.color.accent))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (isCurrent) head.addView(Glass.caption(context, getString(R.string.shots_up_next)))
        addView(head)
        if (shot.description.isNotBlank()) addView(Glass.body(context, shot.description).apply { setPadding(0, dp(4f), 0, 0) })
        if (shot.script.isNotBlank()) addView(Glass.caption(context, "“" + shot.script.lines().first() + if (shot.script.lines().size > 1) "…”" else "”"))
        if (shot.notes.isNotBlank()) addView(Glass.caption(context, shot.notes))
        val pick = shot.pick()
        addView(Glass.caption(context, when {
            shot.takes.isEmpty() -> getString(R.string.shots_no_takes)
            pick == null -> getString(R.string.shots_takes_none_good, shot.takes.size)
            else -> getString(R.string.shots_takes, shot.takes.size, pick.number)
        }).apply { setPadding(0, dp(6f), 0, 0) })
        if (shot.takes.isNotEmpty()) addView(takeStrip(p, shot))
        setOnClickListener { editShot(p, index) }
    }

    private fun takeStrip(p: ShotProject, shot: Shot): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val pick = shot.pick()
        shot.takes.forEach { take ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(8f), dp(10f), 0)
            }
            val thumb = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(Color.rgb(28, 28, 32))
                if (take === pick) {
                    background = ContextCompat.getDrawable(context, R.drawable.bg_glass_pill)
                    setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
                }
            }
            cell.addView(thumb, LinearLayout.LayoutParams(dp(64f), dp(64f)))
            val mark = when (take.good) { true -> " ✓"; false -> " ✗"; null -> "" }
            cell.addView(Glass.caption(this, "T${take.number}$mark" + if (take === pick) " ★" else ""))
            cell.isClickable = true
            cell.setOnClickListener { takeMenu(p, shot, take) }
            row.addView(cell)
            take.videos.firstOrNull()?.second?.let { uri -> loadThumb(uri, thumb) }
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun loadThumb(uri: Uri, into: ImageView) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { contentResolver.loadThumbnail(uri, Size(200, 200), null) }.getOrNull() }
            into.setImageBitmap(bitmap)
        }
    }

    private fun takeMenu(p: ShotProject, shot: Shot, take: ShotTake) {
        val options = mutableListOf(getString(R.string.shots_take_play), getString(R.string.shots_take_use))
        options += getString(R.string.shots_take_good)
        options += getString(R.string.shots_take_no_good)
        options += getString(R.string.shots_take_remove)
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.shots_take_title, shot.number, take.number))
            .setItems(options.toTypedArray()) { _, which ->
                when (which) {
                    0 -> playTake(take)
                    1 -> { shot.chosen = take.number; ShotLists.save(this, p) }
                    2 -> ShotLists.rate(this, p.id, shot.id, take.number, good = true)
                    3 -> ShotLists.rate(this, p.id, shot.id, take.number, good = false)
                    4 -> {
                        shot.takes.remove(take)
                        if (shot.chosen == take.number) shot.chosen = null
                        ShotLists.save(this, p)
                        Toast.makeText(this, R.string.shots_take_removed, Toast.LENGTH_LONG).show()
                    }
                }
                project = ShotLists.load(this, p.id)
                render()
            }
            .show()
    }

    private fun playTake(take: ShotTake) {
        val (label, uri) = take.videos.firstOrNull() ?: return
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).putExtra(PlayerActivity.EXTRA_TITLE, "T${take.number} · $label"))
    }

    // endregion

    // region Editing

    private fun field(hint: String, value: String, multiLine: Boolean = false) = EditText(this).apply {
        setText(value)
        this.hint = hint
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setHintTextColor(ContextCompat.getColor(context, R.color.text_tertiary))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
            (if (multiLine) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0)
        if (multiLine) {
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) }
    }

    /** Add a shot ([index] null) or edit one. */
    private fun editShot(p: ShotProject, index: Int?) {
        val existing = index?.let { p.shots[it] }
        val shot = existing ?: Shot(p.nextId(), ((p.shots.mapNotNull { it.number.takeWhile(Char::isDigit).toIntOrNull() }.maxOrNull() ?: 0) + 1).toString())
        var type = shot.type
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(8f), dp(20f), 0)
        }
        val number = field(getString(R.string.shots_number), shot.number).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS }
        form.addView(number)
        form.addView(Glass.label(this, getString(R.string.shots_size)).apply { setPadding(0, dp(12f), 0, dp(4f)) })
        val types = ChipGroup(this).apply {
            chipSpacingHorizontal = dp(6f)
            chipSpacingVertical = dp(6f)
        }
        fun drawTypes() {
            types.removeAllViews()
            ShotType.entries.forEach { t ->
                types.addView(Glass.pill(this, "${t.code} · ${t.label}", t == type) { type = t; drawTypes() })
            }
        }
        drawTypes()
        form.addView(types)
        val description = field(getString(R.string.shots_description), shot.description, multiLine = true)
        val script = field(getString(R.string.shots_script), shot.script, multiLine = true)
        val notes = field(getString(R.string.shots_notes), shot.notes, multiLine = true)
        form.addView(description)
        form.addView(script)
        form.addView(notes)
        fun apply() {
            shot.number = number.text.toString().trim().ifEmpty { shot.number }
            shot.type = type
            shot.description = description.text.toString().trim()
            shot.script = script.text.toString().trim()
            shot.notes = notes.text.toString().trim()
        }
        var shown: androidx.appcompat.app.AlertDialog? = null
        if (existing != null && index != null) {
            form.addView(ChipGroup(this).apply {
                chipSpacingHorizontal = dp(6f)
                setPadding(0, dp(12f), 0, 0)
                addView(Glass.pill(context, getString(R.string.shots_move_up)) {
                    if (index > 0) { p.shots.removeAt(index); p.shots.add(index - 1, shot); ShotLists.save(context, p); shown?.dismiss(); render() }
                })
                addView(Glass.pill(context, getString(R.string.shots_move_down)) {
                    if (index < p.shots.size - 1) { p.shots.removeAt(index); p.shots.add(index + 1, shot); ShotLists.save(context, p); shown?.dismiss(); render() }
                })
                addView(Glass.pill(context, getString(R.string.shots_delete_shot)) { confirmDeleteShot(p, shot) { shown?.dismiss() } })
            })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.shots_add else R.string.shots_edit)
            .setView(NestedScrollView(this).apply { addView(form) })
            .setPositiveButton(R.string.editor_save) { _, _ ->
                apply()
                if (existing == null) p.shots += shot
                ShotLists.save(this, p)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
        if (existing != null && index != null) {
            dialog.setNeutralButton(R.string.shots_shoot_this) { _, _ ->
                apply()
                ShotLists.save(this, p)
                shoot(p, index)
            }
        }
        shown = dialog.show()
    }

    private fun confirmDeleteShot(p: ShotProject, shot: Shot, then: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.shots_delete_shot_title, shot.number))
            .setMessage(R.string.shots_delete_shot_note)
            .setPositiveButton(R.string.delete) { _, _ ->
                p.shots.remove(shot)
                p.current = p.current.coerceIn(0, (p.shots.size - 1).coerceAtLeast(0))
                ShotLists.save(this, p)
                then()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pasteList(p: ShotProject) {
        val input = field(getString(R.string.shots_paste_hint), "", multiLine = true).apply { minLines = 8 }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shots_paste)
            .setMessage(R.string.shots_paste_note)
            .setView(FrameLayout(this).apply {
                setPadding(dp(20f), dp(4f), dp(20f), 0)
                addView(input)
            })
            .setPositiveButton(R.string.shots_paste_add) { _, _ ->
                val start = (p.shots.mapNotNull { it.number.takeWhile(Char::isDigit).toIntOrNull() }.maxOrNull() ?: 0) + 1
                val shots = ShotLists.parse(input.text.toString(), p.nextId(), start)
                p.shots += shots
                ShotLists.save(this, p)
                Toast.makeText(this, getString(R.string.shots_pasted, shots.size), Toast.LENGTH_SHORT).show()
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askName(titleText: String, value: String, onName: (String) -> Unit) {
        val input = field(getString(R.string.shots_name_hint), value)
        MaterialAlertDialogBuilder(this)
            .setTitle(titleText)
            .setView(FrameLayout(this).apply {
                setPadding(dp(20f), dp(4f), dp(20f), 0)
                addView(input)
            })
            .setPositiveButton(R.string.editor_save) { _, _ -> onName(input.text.toString().trim().ifEmpty { getString(R.string.shots_default_name) }) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // endregion

    // region Shoot and assemble

    /** Puts the camera in shot-list mode at shot [index]. */
    private fun shoot(p: ShotProject, index: Int) {
        p.current = index
        ShotLists.save(this, p)
        ShotLists.setActive(this, p.id)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    /** Joins the chosen take of every shot, in shot order, in the trim and join editor. */
    private fun assemble(p: ShotProject) {
        val picks = p.shots.mapNotNull { it.pick() }
        if (picks.isEmpty()) {
            Toast.makeText(this, R.string.shots_nothing_to_assemble, Toast.LENGTH_LONG).show()
            return
        }
        val missing = p.shots.count { it.pick() == null }
        if (missing > 0) Toast.makeText(this, getString(R.string.shots_missing, missing), Toast.LENGTH_LONG).show()
        // Takes come in every shape they were shot in (9:16, 16:9…).
        val labels = picks.flatMap { t -> t.videos.map { it.first } }.distinct()
        val shapes = OutputFormat.entries.filter { f -> labels.contains(f.label) }
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (shapes.isNotEmpty()) {
            options += getString(R.string.shots_join_all, shapes.joinToString(" + ") { it.label }) to { joinAll(p, picks, shapes) }
        }
        labels.forEach { label ->
            options += getString(R.string.shots_trim_join, label) to {
                EditorActivity.open(this, picks.map { t -> (t.videos.firstOrNull { it.first == label } ?: t.videos.first()).second })
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shots_assemble_which)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    /** Joins the chosen takes in shot order once per shape, all in one go, and saves each cut. */
    private fun joinAll(p: ShotProject, picks: List<ShotTake>, shapes: List<OutputFormat>) {
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(this).apply {
            max = 100
            setIndicatorColor(ContextCompat.getColor(context, R.color.accent))
        }
        val status = Glass.caption(this, "")
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.shots_joining)
            .setView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24f), dp(8f), dp(24f), 0)
                addView(status)
                addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12f) })
            })
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            var made = 0
            var skipped = 0
            try {
                shapes.forEachIndexed { i, format ->
                    status.text = getString(R.string.shots_joining_shape, format.label, i + 1, shapes.size)
                    val uris = picks.mapNotNull { t -> t.videos.firstOrNull { it.first == format.label }?.second }
                    skipped += picks.size - uris.size
                    if (uris.isEmpty()) return@forEachIndexed
                    val resolution = p.outputs[format] ?: SettingsStore.load(this@ShotListActivity).outputs[format] ?: OutputResolution.FHD
                    val (w, h) = format.sizePx(resolution)
                    ShotAssembler.join(this@ShotListActivity, uris, w, h, "${safeName(p.name)}_assembled_${format.label.replace(':', 'x')}") {
                        bar.setProgressCompat(it, true)
                    }
                    made++
                }
                dialog.dismiss()
                Toast.makeText(
                    this@ShotListActivity,
                    getString(R.string.shots_joined, made) + if (skipped > 0) " " + getString(R.string.shots_joined_skipped) else "",
                    Toast.LENGTH_LONG,
                ).show()
                startActivity(Intent(this@ShotListActivity, GalleryActivity::class.java))
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(this@ShotListActivity, getString(R.string.editor_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '_').ifEmpty { "Shots" }

    // endregion

    companion object {
        const val EXTRA_PROJECT = "project"

        fun open(context: Context, projectId: String? = null) {
            context.startActivity(Intent(context, ShotListActivity::class.java).apply { projectId?.let { putExtra(EXTRA_PROJECT, it) } })
        }
    }
}
