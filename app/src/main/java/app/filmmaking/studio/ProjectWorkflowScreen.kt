package app.filmmaking.studio

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.filmmaking.studio.data.*
import org.json.JSONArray
import org.json.JSONObject

private val sections = listOf("Develop", "Story", "Visual", "Planner", "Shoot", "Wrap")
private val sectionKinds = mapOf(
    "Develop" to listOf(WorkflowKind.TREATMENT),
    "Story" to listOf(WorkflowKind.SCRIPT, WorkflowKind.BEAT),
    "Visual" to listOf(WorkflowKind.RECIPE, WorkflowKind.REFERENCE),
    "Planner" to listOf(WorkflowKind.DAY, WorkflowKind.SETUP, WorkflowKind.CHECKLIST),
    "Shoot" to listOf(WorkflowKind.CONTINUITY)
)

private fun recordDetail(item: StudioRecord): String {
    val d = JSONObject(item.payload)
    return when (item.collection) {
        "treatments" -> d.optString("premise")
        "artifacts" -> d.optString("draft")
        "beats" -> d.optString("visual")
        "recipes" -> d.optString("guidance")
        "shootDays" -> d.optString("notes")
        "setups" -> d.optString("instructions")
        "checklist" -> d.optString("note")
        "continuity" -> d.optString("observed")
        "references" -> d.optString("notes")
        else -> ""
    }
}

private fun JSONArray?.ids(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
private fun shotLabel(s: StudioRecord) = data(s).optString("code") + " " + s.title

/** Context the AI is allowed to see: the film plus the selected items only. */
private fun filmSources(project: StudioRecord, memories: List<JSONObject> = emptyList()): JSONArray {
    val p = data(project)
    val text = listOf(p.optString("name"), p.optString("concept"), p.optString("logline"), p.optString("mood"))
        .filter { it.isNotBlank() }.joinToString("\n").ifBlank { project.title }
    val out = JSONArray()
    // Approved Creative DNA first (web layer A), then the film itself.
    memories.forEach { m -> out.put(JSONObject().put("layer", "A").put("label", if (m.optString("scope") == "identity") "Memory" else "Project choice").put("text", m.optString("text").take(500))) }
    return out.put(JSONObject().put("layer", "C").put("label", "Current film").put("text", text.take(8000)))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkflowScreen(
    vm: StudioViewModel,
    project: StudioRecord,
    scenes: List<StudioRecord>,
    shots: List<StudioRecord>,
    allRecords: List<StudioRecord>,
    onExport: (String) -> Unit,
    onImportFootage: () -> Unit,
    onPickStoryboard: (String) -> Unit,
    onPickContinuityPhoto: (String, String) -> Unit,
    onShootMode: () -> Unit,
    section: String,
    onSection: (String) -> Unit
) {
    var creating by remember { mutableStateOf<WorkflowKind?>(null) }
    var editing by remember { mutableStateOf<StudioRecord?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var newDetail by remember { mutableStateOf("") }
    var shotId by remember { mutableStateOf<String?>(null) }
    var shotDropdown by remember { mutableStateOf(false) }

    val records = allRecords.filter { it.deletedAt == null && data(it).optString("projectId") == project.id }
    fun of(c: String) = records.filter { it.collection == c }
    val signedIn = vm.session != null

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = sections.indexOf(section).coerceAtLeast(0), edgePadding = 8.dp) {
            sections.forEach { tab -> Tab(selected = section == tab, onClick = { onSection(tab) }, text = { Text(tab) }) }
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(vertical = 10.dp)) {
            when (section) {
                "Develop" -> item { AiProposals(vm, project, "treatments") }
                "Story" -> {
                    item { AiProposals(vm, project, "beats") }
                    item { BeatBoard(vm, project, of("beats"), shots, scenes) }
                    item { ShotProposalPanel(vm, of("shotProposals")) }
                }
                "Visual" -> {
                    item { StoryboardPanel(vm, project, shots, scenes, of("storyboards"), onPickStoryboard) }
                    item { DiagramPanel(vm, project, scenes, shots, of("diagrams"), allRecords.filter { it.collection == "recipes" && it.deletedAt == null }) }
                }
                "Planner" -> {
                    item { DayPlanner(vm, project, shots, of("shootDays"), of("scheduleProposals")) }
                    item { SetupPanel(vm, shots, of("setups"), of("diagrams")) }
                    item { ChecklistPanel(vm, project, of("checklist")) }
                }
                "Shoot" -> {
                    item {
                        Button(onClick = onShootMode, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                            Text("Enter Shoot Mode (full screen, works offline)")
                        }
                    }
                    item { CoveragePanel(vm, allRecords, project, scenes) }
                }
                "Wrap" -> item {
                    WrapSummary(vm, project, shots, records, allRecords, onExport, onImportFootage)
                }
            }
            val kinds = sectionKinds[section].orEmpty()
            if (kinds.isNotEmpty()) {
                item {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        kinds.forEach { kind ->
                            OutlinedButton(onClick = {
                                creating = kind; editing = null; newTitle = ""; newDetail = ""; shotId = shots.firstOrNull()?.id
                            }) { Text("+ " + kind.label) }
                        }
                    }
                }
                val ordered = kinds.flatMap { kind -> records.filter { it.collection == kind.collection }.sortedByDescending { it.updatedAt } }
                if (ordered.isEmpty()) item {
                    Text("No " + section.lowercase() + " notes yet. Create one above to begin.", style = MaterialTheme.typography.bodyMedium)
                }
                items(ordered, key = { it.collection + "/" + it.id }) { item ->
                    val kind = kinds.first { it.collection == item.collection }
                    WorkflowCard(vm, item, kind, shots, onPickContinuityPhoto) {
                        editing = item; creating = kind; newTitle = item.title; newDetail = recordDetail(item)
                        shotId = data(item).optString("shotId").takeIf { it.isNotBlank() }
                    }
                }
            }
        }
    }

    val kind = creating
    if (kind != null) {
        AlertDialog(
            onDismissRequest = { creating = null; editing = null },
            title = { Text((if (editing == null) "Add " else "Edit ") + kind.label) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(newTitle, { newTitle = it }, Modifier.fillMaxWidth(),
                        label = { Text(if (kind == WorkflowKind.CONTINUITY) "Planned condition" else "Title") })
                    OutlinedTextField(newDetail, { newDetail = it }, Modifier.fillMaxWidth(), label = { Text(kind.detailLabel) }, minLines = 4)
                    if (kind == WorkflowKind.CONTINUITY && editing == null) {
                        Box {
                            OutlinedButton(onClick = { shotDropdown = true }) {
                                Text("Shot: " + (shots.find { it.id == shotId }?.let(::shotLabel) ?: "Select shot"))
                            }
                            DropdownMenu(expanded = shotDropdown, onDismissRequest = { shotDropdown = false }) {
                                shots.forEach { s -> DropdownMenuItem(text = { Text(shotLabel(s)) }, onClick = { shotId = s.id; shotDropdown = false }) }
                            }
                        }
                    }
                    if (kind == WorkflowKind.CONTINUITY && editing != null)
                        Text("Corrections keep the original observation in the history.", style = MaterialTheme.typography.bodySmall)
                    if (kind == WorkflowKind.SCRIPT)
                        Text("Approving saves an immutable version. Later edits only change the working draft.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(enabled = newTitle.isNotBlank() && (kind != WorkflowKind.CONTINUITY || editing != null || shotId != null), onClick = {
                    val item = editing
                    if (item == null) vm.createWorkflow(project.id, kind, newTitle.trim(), newDetail, shotId)
                    else vm.updateWorkflow(item.collection, item.id, newTitle.trim(), newDetail)
                    creating = null; editing = null
                }) { Text("Save offline") }
            },
            dismissButton = { TextButton(onClick = { creating = null; editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun AiProposals(vm: StudioViewModel, project: StudioRecord, op: String) {
    var instructions by rememberSaveable(project.id + op) { mutableStateOf("") }
    val generated = vm.generatedOptions?.takeIf { it.projectId == project.id && it.op == op }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(instructions, { instructions = it }, Modifier.fillMaxWidth(),
            label = { Text("Optional direction for AI proposals") }, maxLines = 3)
        OutlinedButton(enabled = vm.session != null && !vm.generating, onClick = { vm.generateOptions(project, op, instructions) }) {
            Text(if (vm.generating) "Generating…" else "Suggest " + if (op == "treatments") "2 treatments" else "2 visual beats")
        }
        if (vm.session == null) Text("Sign in via Settings to generate AI proposals.", style = MaterialTheme.typography.bodySmall)
        if (generated != null) {
            Text("AI suggestions — only accepted items will be saved", style = MaterialTheme.typography.labelLarge)
            generated.estimatedCostUsd?.let { Text("Estimated model cost: $" + String.format("%.4f", it), style = MaterialTheme.typography.labelSmall) }
            generated.items.forEachIndexed { index, idea ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(idea.optString("title"), style = MaterialTheme.typography.titleMedium)
                        Text(if (op == "treatments") idea.optString("premise") else idea.optString("visual"))
                        TextButton(onClick = { vm.acceptGenerated(index) }) { Text("Accept as editable draft") }
                    }
                }
            }
            TextButton(onClick = vm::clearGenerated) { Text("Discard remaining AI suggestions") }
        }
    }
}

@Composable
private fun WorkflowCard(vm: StudioViewModel, item: StudioRecord, kind: WorkflowKind, shots: List<StudioRecord>,
                         pickPhoto: (String, String) -> Unit = { _, _ -> }, edit: () -> Unit) {
    val json = data(item)
    var critiqueOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind.label, style = MaterialTheme.typography.labelSmall)
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (kind == WorkflowKind.CHECKLIST) Text("Status: " + json.optString("status") + if (json.optBoolean("optional")) " · optional" else "")
            val detail = recordDetail(item)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 7)
            when (kind) {
                WorkflowKind.TREATMENT -> Text("Status: " + json.optString("status"), style = MaterialTheme.typography.labelMedium)
                WorkflowKind.SCRIPT -> {
                    val versions = json.optJSONArray("versions") ?: JSONArray()
                    Text(versions.length().toString() + " saved version(s)" +
                        (if (json.optString("approvedVersionId").isNotBlank()) " · one approved" else ""), style = MaterialTheme.typography.labelMedium)
                    for (i in versions.length() - 1 downTo maxOf(0, versions.length() - 3)) {
                        val v = versions.getJSONObject(i)
                        Text("• " + v.optString("status") + " · " + v.optString("author") + " · " + v.optString("note").take(60),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                WorkflowKind.CONTINUITY -> {
                    Text("Shot: " + (shots.find { it.id == json.optString("shotId") }?.let(::shotLabel) ?: "?") +
                        " · " + (json.optJSONArray("history")?.length() ?: 0) + " earlier observation(s)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("record" to "On record", "speculative" to "Speculative").forEach { (b, label) ->
                            FilterChip(selected = json.optString("basis") == b, onClick = { vm.act { vm.features.setContinuity(item.id, basis = b) } }, label = { Text(label) })
                        }
                        FilterChip(selected = json.optBoolean("resolved"), onClick = {
                            vm.act { vm.features.setContinuity(item.id, resolved = !json.optBoolean("resolved")) }
                        }, label = { Text(if (json.optBoolean("resolved")) "Resolved" else "Open") })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("planned" to "Planned photo", "actual" to "Actual photo").forEach { (which, label) ->
                            val img = rememberDataImage(json.optString(which + "Image").ifBlank { null })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (img != null) androidx.compose.foundation.Image(img, label, Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                Text(label, style = MaterialTheme.typography.labelSmall)
                                Row {
                                    TextButton(onClick = { pickPhoto(item.id, which) }) { Text(if (img == null) "Add" else "Replace") }
                                    if (img != null) TextButton(onClick = { vm.act { vm.features.setContinuityPhoto(item.id, which, null) } }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
                else -> Unit
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!(kind == WorkflowKind.TREATMENT && json.optString("status") == "approved")) TextButton(onClick = edit) { Text("Edit") }
                if (kind == WorkflowKind.TREATMENT && json.optString("status") != "approved")
                    TextButton(onClick = { vm.approveTreatment(json.optString("projectId"), item.id) }) { Text("Approve") }
                if (kind == WorkflowKind.SCRIPT) {
                    TextButton(onClick = { vm.approveScript(item.id) }) { Text("Approve draft version") }
                    TextButton(enabled = vm.session != null && !vm.aiBusy, onClick = { critiqueOpen = true }) { Text("AI critique") }
                }
                TextButton(onClick = { vm.act { vm.features.removeRecord(item.collection, item.id) } }) { Text("Delete") }
            }
        }
    }
    if (critiqueOpen) CritiqueDialog(vm, item) { critiqueOpen = false }
}

/** #8: critique items are shown; saving the revised draft creates a NEW draft version only. */
@Composable
private fun CritiqueDialog(vm: StudioViewModel, artifact: StudioRecord, close: () -> Unit) {
    var result by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(artifact.id) {
        val a = data(artifact)
        vm.aiCreate(a.optString("projectId"), "critique", "", JSONArray().put(JSONObject().put("kind", "artifact")
            .put("id", artifact.id).put("label", artifact.title).put("text", a.optString("draft").take(12000))), JSONArray()) { r ->
            result = r; "Critique ready: review before saving"
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Script critique") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = result
            if (r == null) Text(if (vm.aiBusy) "Asking the script doctor…" else "No critique yet")
            else {
                Text(r.optString("summary"))
                val items = r.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    Text("• [" + it.optString("kind") + "] “" + it.optString("original").take(120) + "” → " + it.optString("suggestion"),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }, confirmButton = {
        val revised = result?.optString("revisedDraft").orEmpty()
        TextButton(enabled = revised.isNotBlank(), onClick = {
            vm.act { vm.features.addDraftVersion(artifact.id, revised, "AI critique revision", "assistant"); vm.report("Saved as a new DRAFT version; the approved text is unchanged") }
            close()
        }) { Text("Save revision as new draft") }
    }, dismissButton = { TextButton(onClick = close) { Text("Close") } })
}

/** #6/#7: drag (or ↑/↓) rewrites `order` only; shot links survive; AI shots become pending proposals. */
@Composable
private fun BeatBoard(vm: StudioViewModel, project: StudioRecord, beats: List<StudioRecord>, shots: List<StudioRecord>, scenes: List<StudioRecord>) {
    var linking by remember { mutableStateOf<StudioRecord?>(null) }
    val sorted = beats.sortedBy { data(it).optDouble("order") }
    val stored = sorted.map { it.id }
    // Live order while dragging; reset whenever the stored order changes.
    val live = remember(stored) { mutableStateListOf<String>().apply { addAll(stored) } }
    val heights = remember { mutableStateMapOf<String, Int>() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val byId = sorted.associateBy { it.id }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Beat board (" + sorted.size + ")", style = MaterialTheme.typography.titleMedium)
        if (sorted.isEmpty()) Text("Add visual beats below or accept AI beats above.", style = MaterialTheme.typography.bodySmall)
        else Text("Drag ≡ to reorder. Shot links stay with their beat.", style = MaterialTheme.typography.bodySmall)
        live.forEachIndexed { i, id ->
          // key(id): a card keeps its composition (and its in-flight drag gesture) when it changes slot.
          val beat = byId[id] ?: return@forEachIndexed
          key(id) {
            val b = data(beat); val linked = b.optJSONArray("shotIds").ids()
            val isDragged = dragging == id
            OutlinedCard(Modifier.fillMaxWidth().zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                .onGloballyPositioned { heights[id] = it.size.height }) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("≡", Modifier.size(48.dp).wrapContentSize().semantics { contentDescription = "Drag " + beat.title }
                            .pointerInput(id, stored) {
                                detectDragGestures(
                                    onDragStart = { dragging = id; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        change.consume(); dragOffset += amount.y
                                        val at = live.indexOf(id)
                                        val gap = 6.dp.toPx()
                                        if (dragOffset > 0 && at < live.lastIndex) {
                                            val next = (heights[live[at + 1]] ?: 0) + gap
                                            if (dragOffset > next / 2) { live.add(at + 1, live.removeAt(at)); dragOffset -= next }
                                        } else if (dragOffset < 0 && at > 0) {
                                            val prev = (heights[live[at - 1]] ?: 0) + gap
                                            if (-dragOffset > prev / 2) { live.add(at - 1, live.removeAt(at)); dragOffset += prev }
                                        }
                                    },
                                    onDragEnd = {
                                        dragging = null; dragOffset = 0f
                                        val order = live.toList()
                                        if (order != stored) vm.act { vm.features.reorderBeats(project.id, order) }
                                    },
                                    onDragCancel = { dragging = null; dragOffset = 0f; live.clear(); live.addAll(stored) }
                                )
                            }, style = MaterialTheme.typography.titleLarge)
                        Text((i + 1).toString() + ". " + beat.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    }
                    Text("Linked shots: " + (shots.filter { it.id in linked }.joinToString { data(it).optString("code") }.ifBlank { "none" }),
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled = i > 0, onClick = { vm.act { vm.features.moveBeat(project.id, beat.id, true) } }) { Text("↑ Up") }
                        TextButton(enabled = i < live.lastIndex, onClick = { vm.act { vm.features.moveBeat(project.id, beat.id, false) } }) { Text("↓ Down") }
                        TextButton(onClick = { linking = beat }) { Text("Link shots") }
                        val scene = shots.firstOrNull { it.id in linked }?.let { data(it).optString("sceneId") } ?: scenes.firstOrNull()?.id
                        TextButton(enabled = vm.session != null && !vm.aiBusy && scene != null, onClick = {
                            vm.aiCreate(project.id, "shots", "", JSONArray().put(JSONObject().put("kind", "beat").put("id", beat.id)
                                .put("label", beat.title).put("text", listOf(b.optString("intention"), b.optString("visual"), b.optString("passage")).joinToString("\n"))),
                                filmSources(project, vm.memoriesFor(project.id)), count = 3) { r ->
                                val arr = r.optJSONArray("shots") ?: JSONArray()
                                vm.features.saveShotProposals(project.id, scene!!, beat.id, (0 until arr.length()).map { arr.getJSONObject(it) }, "ai:claude-haiku-4-5")
                                arr.length().toString() + " shot proposal(s) waiting for review — nothing was added to the shot list"
                            }
                        }) { Text("AI shot ideas") }
                    }
                }
            }
          }
        }
    }
    linking?.let { beat ->
        var chosen by remember(beat.id) { mutableStateOf(data(beat).optJSONArray("shotIds").ids().toSet()) }
        AlertDialog(onDismissRequest = { linking = null }, title = { Text("Shots for " + beat.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shots.forEach { s ->
                    Row { Checkbox(checked = s.id in chosen, onCheckedChange = { on -> chosen = if (on) chosen + s.id else chosen - s.id }); Text(shotLabel(s)) }
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.act { vm.features.linkBeatShots(beat.id, chosen.toList()) }; linking = null }) { Text("Save links") } },
            dismissButton = { TextButton(onClick = { linking = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ShotProposalPanel(vm: StudioViewModel, proposals: List<StudioRecord>) {
    val pending = proposals.filter { data(it).optString("status") == "pending" }
    if (pending.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shot proposals awaiting review (" + pending.size + ")", style = MaterialTheme.typography.titleMedium)
        pending.forEach { p ->
            val b = data(p)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text(listOf(b.optString("shotSize"), b.optString("lens"), b.optString("movement")).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                    if (b.optString("whyItWorks").isNotBlank()) Text(b.optString("whyItWorks"), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.act { vm.features.decideShotProposal(p.id, true); vm.report("Shot added to the scene; it keeps its beat and proposal links") } }) { Text("Accept as shot") }
                        TextButton(onClick = { vm.act { vm.features.decideShotProposal(p.id, false) } }) { Text("Reject") }
                    }
                }
            }
        }
    }
}

/** #9: photo / sketch / archive passage / optional paid AI frame; versions kept; one chosen frame per shot. */
@Composable
private fun StoryboardPanel(vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, scenes: List<StudioRecord>, frames: List<StudioRecord>, pick: (String) -> Unit) {
    var sketching by remember { mutableStateOf<StudioRecord?>(null) }
    var archiveFor by remember { mutableStateOf<StudioRecord?>(null) }
    var aiFor by remember { mutableStateOf<StudioRecord?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Storyboards", style = MaterialTheme.typography.titleMedium)
        if (shots.isEmpty()) Text("Add shots first.", style = MaterialTheme.typography.bodySmall)
        shots.forEach { s ->
            val mine = frames.filter { data(it).optString("shotId") == s.id }.sortedByDescending { data(it).optInt("version") }
            val chosen = mine.firstOrNull { data(it).optBoolean("chosen") }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shotLabel(s) + " · " + mine.size + " frame(s)", style = MaterialTheme.typography.titleSmall)
                    if (chosen != null) {
                        FrameView(data(chosen), Modifier.fillMaxWidth())
                        AnnotationEditor(vm, chosen)
                    } else if (mine.isNotEmpty()) Text("No frame chosen yet — tap a version below.", style = MaterialTheme.typography.bodySmall)
                    if (mine.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mine.forEach { f ->
                            val fb = data(f)
                            Column(Modifier.width(132.dp)) {
                                FrameView(fb, Modifier.fillMaxWidth())
                                Text("v" + fb.optInt("version") + " · " + fb.optString("source") + if (fb.optBoolean("chosen")) " · ✓ chosen" else "",
                                    style = MaterialTheme.typography.labelSmall)
                                if (!fb.optBoolean("chosen")) TextButton(onClick = { vm.act { vm.features.chooseStoryboard(f.id) } }) { Text("Choose") }
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { pick(s.id) }) { Text("Add frame from photos") }
                        TextButton(onClick = { sketching = s }) { Text("Sketch") }
                        TextButton(enabled = vm.session != null, onClick = { archiveFor = s }) { Text("From archive") }
                        TextButton(enabled = vm.session != null && !vm.aiBusy, onClick = { aiFor = s }) { Text("AI frame") }
                    }
                }
            }
        }
    }
    sketching?.let { shot ->
        val base = frames.firstOrNull { data(it).optString("shotId") == shot.id && data(it).optBoolean("chosen") }?.let { data(it).optString("imageUri").ifBlank { null } }
        AlertDialog(onDismissRequest = { sketching = null }, title = { Text("Sketch " + data(shot).optString("code")) }, text = {
            SketchPad(base, onSave = { strokes ->
                vm.act { vm.features.addStoryboard(project.id, shot.id, base, "drawing", JSONObject(), false, strokes = strokes); vm.report("Sketch saved as a new storyboard version") }
                sketching = null
            }, onCancel = { sketching = null })
        }, confirmButton = {})
    }
    archiveFor?.let { shot ->
        var q by remember(shot.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { archiveFor = null }, title = { Text("Reference from your archive") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("e.g. steam, window light") }, singleLine = true)
                Button(enabled = q.isNotBlank() && !vm.aiBusy, onClick = { vm.searchArchive(q) }) { Text("Search") }
                vm.archiveResults.forEach { h ->
                    OutlinedCard(onClick = {
                        vm.act { vm.features.addStoryboard(project.id, shot.id, null, "archive", JSONObject(), false,
                            archiveRef = JSONObject().put("documentId", h.optString("document_id")).put("title", h.optString("title")).put("excerpt", h.optString("content").take(600)))
                            vm.report("Archive passage attached as a storyboard reference") }
                        archiveFor = null
                    }, modifier = Modifier.fillMaxWidth()) { Text(h.optString("title") + " — " + h.optString("content").take(140) + "…", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall) }
                }
                Text("Your archive holds text, so this attaches a cited passage as the reference.", style = MaterialTheme.typography.labelSmall)
            }
        }, confirmButton = {}, dismissButton = { TextButton(onClick = { archiveFor = null }) { Text("Close") } })
    }
    aiFor?.let { shot ->
        val sb = data(shot)
        val scene = scenes.firstOrNull { it.id == sb.optString("sceneId") }?.let { data(it) }
        val taste = vm.memoriesFor(project.id).map { it.optString("text") }
        var idea by remember(shot.id) { mutableStateOf("") }
        var editing by remember(shot.id) { mutableStateOf(false) }
        var edited by remember(shot.id) { mutableStateOf("") }
        val brief = remember(shot.id, idea, sb.toString(), scene?.toString(), taste) {
            StoryboardBrief.build(idea, sb, scene, data(project), taste)
        }
        val prompt = if (editing) edited else brief.text
        val cost = "%.3f".format(StudioCreativeClient.STORYBOARD_IMAGE_COST_USD)
        AlertDialog(onDismissRequest = { aiFor = null }, title = { Text("Generate a conceptual frame?") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(idea, { idea = it }, Modifier.fillMaxWidth(), label = { Text("Your idea (a few words is fine)") },
                    placeholder = { Text(shot.title) }, maxLines = 3)
                Text("Brief sent to the image model, built from this shot's plan:", style = MaterialTheme.typography.labelMedium)
                if (editing) {
                    OutlinedTextField(edited, { edited = it.take(StoryboardBrief.MAX_CHARS) }, Modifier.fillMaxWidth(), maxLines = 12,
                        label = { Text("Edited brief") })
                    TextButton(onClick = { editing = false }) { Text("Rebuild from the shot plan") }
                } else {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            brief.lines.forEach { l -> Text(l.label + " — " + l.text, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    TextButton(onClick = { edited = brief.text; editing = true }) { Text("Edit the brief") }
                }
                Text(prompt.length.toString() + " / " + StoryboardBrief.MAX_CHARS + " characters · " + brief.orientation.label + " frame" +
                    " · the server adds a rough pencil-and-wash storyboard style.", style = MaterialTheme.typography.labelSmall)
                Text("One image with OpenAI (gpt-image-1, low quality). Estimated cost: $" + cost + ", counted against your daily AI limit. " +
                    "AI frames are conceptual references, not technically accurate. Fill in the shot's camera and lighting plan for a closer frame.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = {
            TextButton(enabled = prompt.trim().length >= 5, onClick = {
                vm.generateStoryboardImage(project.id, shot.id, prompt, brief.orientation.wire); aiFor = null
            }) { Text("Generate (≈ $" + cost + ")") }
        }, dismissButton = { TextButton(onClick = { aiFor = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AnnotationEditor(vm: StudioViewModel, frame: StudioRecord) {
    val ann = data(frame).optJSONObject("annotations") ?: JSONObject()
    val values = remember(frame.id, ann.toString()) { mutableStateMapOf<String, String>().apply { FeatureRepository.ANNOTATION_KEYS.forEach { put(it, ann.optString(it)) } } }
    val labels = mapOf("composition" to "Composition", "movement" to "Movement", "lighting" to "Lighting", "intent" to "Visual intent")
    FeatureRepository.ANNOTATION_KEYS.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { k -> OutlinedTextField(values[k].orEmpty(), { values[k] = it }, Modifier.weight(1f), label = { Text(labels.getValue(k)) }, maxLines = 3) }
        }
    }
    val changed = FeatureRepository.ANNOTATION_KEYS.any { values[it].orEmpty() != ann.optString(it) }
    if (changed) TextButton(onClick = { vm.act { vm.features.setStoryboardAnnotations(frame.id, values.toMap()) } }) { Text("Save frame notes") }
}

/** #10/#11/#12: element list in metres, versioned; recipes apply as copies; AI setup is previewed first. */
@Composable
private fun DiagramPanel(vm: StudioViewModel, project: StudioRecord, scenes: List<StudioRecord>, shots: List<StudioRecord>,
                         diagrams: List<StudioRecord>, recipes: List<StudioRecord>) {
    var name by rememberSaveable(project.id) { mutableStateOf("") }
    var sceneId by remember { mutableStateOf(scenes.firstOrNull()?.id) }
    var adding by remember { mutableStateOf<StudioRecord?>(null) }
    var preview by remember { mutableStateOf<Pair<StudioRecord, JSONArray>?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Setup diagrams (metres)", style = MaterialTheme.typography.titleMedium)
        if (scenes.isEmpty()) { Text("Add a scene first.", style = MaterialTheme.typography.bodySmall); return@Column }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            scenes.forEach { sc -> FilterChip(selected = sceneId == sc.id, onClick = { sceneId = sc.id }, label = { Text("Scene " + data(sc).optInt("number")) }) }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("New diagram name") }, singleLine = true)
        Button(enabled = name.isNotBlank() && sceneId != null, onClick = { vm.act { vm.features.createDiagram(project.id, sceneId!!, null, name) }; name = "" }) { Text("Create diagram") }
        recipes.forEach { r ->
            TextButton(enabled = sceneId != null, onClick = { vm.act { vm.features.applyRecipe(r.id, project.id, sceneId!!, null); vm.report("Recipe copied into a new diagram; the recipe itself is unchanged") } }) {
                Text("Apply lookbook recipe: " + r.title)
            }
        }
        diagrams.sortedBy { data(it).optString("name") + data(it).optInt("version") }.forEach { d ->
            val b = data(d); val els = b.optJSONArray("elements") ?: JSONArray()
            var selected by remember(d.id) { mutableStateOf<String?>(null) }
            val fork: (String) -> Unit = { to -> if (to != d.id) vm.report("Approved diagram kept; edit saved as a new draft version") }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(b.optString("name") + " v" + b.optInt("version") + " · " + b.optString("status") + " · " +
                        b.optDouble("width", 10.0).toInt() + "×" + b.optDouble("height", 8.0).toInt() + " m", style = MaterialTheme.typography.titleSmall)
                    DiagramCanvas(b, selected, { selected = it }) { id, x, y -> vm.act { fork(vm.features.moveDiagramElement(d.id, id, x, y)) } }
                    Text("Tap to select, drag to move (1 m grid).", style = MaterialTheme.typography.labelSmall)
                    val sel = (0 until els.length()).map { els.getJSONObject(it) }.firstOrNull { it.optString("id") == selected }
                    if (sel != null) {
                        val sid = sel.optString("id")
                        Text("Selected: " + sel.optString("type") + " “" + sel.optString("label") + "” at " + sel.optDouble("x") + " m, " +
                            sel.optDouble("y") + " m, " + sel.optDouble("rotation", 0.0).toInt() + "°", style = MaterialTheme.typography.bodySmall)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton(onClick = { vm.act { fork(vm.features.rotateDiagramElement(d.id, sid, -15.0)) } }) { Text("⟲ 15°") }
                            TextButton(onClick = { vm.act { fork(vm.features.rotateDiagramElement(d.id, sid, 15.0)) } }) { Text("⟳ 15°") }
                            listOf("←" to (-0.5 to 0.0), "→" to (0.5 to 0.0), "↑" to (0.0 to -0.5), "↓" to (0.0 to 0.5)).forEach { (arrow, delta) ->
                                TextButton(onClick = { vm.act { fork(vm.features.moveDiagramElement(d.id, sid, sel.optDouble("x") + delta.first, sel.optDouble("y") + delta.second)) } }) { Text(arrow) }
                            }
                            TextButton(onClick = { vm.act { fork(vm.features.removeDiagramElement(d.id, sid)) }; selected = null }) { Text("Delete element") }
                        }
                    }
                    for (i in 0 until els.length()) {
                        val e = els.getJSONObject(i)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.optString("type") + " “" + e.optString("label") + "” at " + e.optDouble("x") + "m, " + e.optDouble("y") + "m",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.act { fork(vm.features.removeDiagramElement(d.id, e.optString("id"))) } }) { Text("×") }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick = { adding = d }) { Text("+ Element") }
                        if (b.optString("status") != "approved") TextButton(onClick = { vm.act { vm.features.approveDiagram(d.id) } }) { Text("Approve") }
                        TextButton(onClick = { vm.act { vm.features.saveDiagramAsRecipe(d.id, b.optString("name") + " recipe") } }) { Text("Save as recipe") }
                        TextButton(enabled = vm.session != null && !vm.aiBusy, onClick = {
                            val text = (0 until els.length()).joinToString("\n") { i -> val e = els.getJSONObject(i)
                                "id=" + e.optString("id") + " " + e.optString("type") + " \"" + e.optString("label") + "\" x=" + e.optDouble("x") + " y=" + e.optDouble("y") }
                            vm.aiCreate(project.id, "setup", "", JSONArray().put(JSONObject().put("kind", "diagram").put("id", d.id)
                                .put("label", b.optString("name")).put("text", text.ifBlank { "(empty diagram)" })), filmSources(project, vm.memoriesFor(project.id))) { r ->
                                preview = d to vm.features.previewSetupChanges(b, r.optJSONArray("changes") ?: JSONArray())
                                "AI setup suggestion ready: preview before accepting. " + r.optString("explanation").take(160)
                            }
                        }) { Text("AI cinematographer") }
                    }
                }
            }
        }
    }
    adding?.let { d ->
        var type by remember { mutableStateOf("camera") }
        var x by remember { mutableStateOf("5") }; var y by remember { mutableStateOf("4") }; var label by remember { mutableStateOf("") }
        var open by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { adding = null }, title = { Text("Add element") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box { OutlinedButton(onClick = { open = true }) { Text("Type: $type") }
                    DropdownMenu(open, { open = false }) { FeatureRepository.DIAGRAM_TYPES.forEach { t -> DropdownMenuItem({ Text(t) }, { type = t; open = false }) } } }
                OutlinedTextField(x, { x = it }, label = { Text("x (metres from left)") }, singleLine = true)
                OutlinedTextField(y, { y = it }, label = { Text("y (metres from top)") }, singleLine = true)
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
            }
        }, confirmButton = {
            TextButton(enabled = x.toDoubleOrNull() != null && y.toDoubleOrNull() != null, onClick = {
                vm.act { val to = vm.features.addDiagramElement(d.id, type, x.toDouble(), y.toDouble(), label)
                    if (to != d.id) vm.report("Approved diagram kept; edit saved as a new draft version") }
                adding = null
            }) { Text("Add") }
        }, dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } })
    }
    preview?.let { (d, els) ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("Preview AI setup") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DiagramCanvas(JSONObject(data(d).toString()).put("elements", els), null, {}) { _, _, _ -> }
                for (i in 0 until els.length()) { val e = els.getJSONObject(i)
                    Text(e.optString("type") + " “" + e.optString("label") + "” " + e.optDouble("x") + "m, " + e.optDouble("y") + "m", style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(onClick = { vm.act { vm.features.acceptSetupChanges(d.id, els) }; preview = null }) { Text("Accept changes") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("Discard") } })
    }
}

/** #13/#14: day order editing + approved history; AI schedule only applied on Approve. */
@Composable
private fun DayPlanner(vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, days: List<StudioRecord>, proposals: List<StudioRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shooting order", style = MaterialTheme.typography.titleMedium)
        if (days.isEmpty()) Text("Add a shoot day below, then build its order.", style = MaterialTheme.typography.bodySmall)
        days.forEach { day ->
            val b = data(day)
            val approved = b.optJSONArray("order").ids()
            var draft by remember(day.id, day.updatedAt) { mutableStateOf(b.optJSONArray("draftOrder")?.ids() ?: approved) }
            var addOpen by remember { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(day.title + " · " + approved.size + " approved · " + (b.optJSONArray("history")?.length() ?: 0) + " earlier order(s)",
                        style = MaterialTheme.typography.titleSmall)
                    draft.forEachIndexed { i, id ->
                        val s = shots.find { it.id == id } ?: return@forEachIndexed
                        Row {
                            Text((i + 1).toString() + ". " + shotLabel(s), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(enabled = i > 0, onClick = { draft = draft.toMutableList().also { java.util.Collections.swap(it, i, i - 1) } }) { Text("↑") }
                            TextButton(onClick = { draft = draft - id }) { Text("×") }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Box { TextButton(onClick = { addOpen = true }) { Text("+ Shot") }
                            DropdownMenu(addOpen, { addOpen = false }) {
                                shots.filter { it.id !in draft }.forEach { s -> DropdownMenuItem({ Text(shotLabel(s)) }, { draft = draft + s.id; addOpen = false }) }
                            } }
                        TextButton(enabled = draft != approved && draft.isNotEmpty(), onClick = {
                            vm.act { vm.features.approveDayOrder(day.id, draft, "Edited by hand"); vm.report("Shooting order approved; narrative order unchanged") }
                        }) { Text("Approve order") }
                        TextButton(enabled = vm.session != null && !vm.aiBusy && shots.isNotEmpty(), onClick = {
                            val text = shots.joinToString("\n") { "id=" + it.id + " " + shotLabel(it) + " setup " + (data(it).optJSONObject("production")?.optString("setup") ?: "") }
                            vm.aiCreate(project.id, "schedule", "", JSONArray().put(JSONObject().put("kind", "day").put("id", day.id).put("label", day.title).put("text", text)), filmSources(project, vm.memoriesFor(project.id))) { r ->
                                vm.features.saveScheduleProposal(project.id, day.id, r, "ai:claude-haiku-4-5"); "AI schedule proposal saved as pending — approve it to apply"
                            }
                        }) { Text("AI schedule") }
                    }
                }
            }
        }
        proposals.filter { data(it).optString("status") == "pending" }.forEach { p ->
            val b = data(p)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("Pending schedule · ~" + b.optInt("estimatedMin") + " min (estimate)", style = MaterialTheme.typography.titleSmall)
                    Text(b.optJSONArray("order").ids().mapNotNull { id -> shots.find { it.id == id }?.let { data(it).optString("code") } }.joinToString(" → "))
                    Text(b.optString("reasoning").take(300), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { vm.act { vm.features.decideScheduleProposal(p.id, true) } }) { Text("Approve") }
                        TextButton(onClick = { vm.act { vm.features.decideScheduleProposal(p.id, false) } }) { Text("Reject") }
                    }
                }
            }
        }
    }
}

/** #15: a shot belongs to at most one setup; approved diagrams are never edited by setup changes. */
@Composable
private fun SetupPanel(vm: StudioViewModel, shots: List<StudioRecord>, setups: List<StudioRecord>, diagrams: List<StudioRecord>) {
    var editing by remember { mutableStateOf<StudioRecord?>(null) }
    if (setups.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Setups and impact", style = MaterialTheme.typography.titleMedium)
        setups.forEach { st ->
            val ids = data(st).optJSONArray("shotIds").ids()
            val approvedDiagrams = diagrams.count { data(it).optString("shotId") in ids && data(it).optString("status") == "approved" }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(st.title, style = MaterialTheme.typography.titleSmall)
                    Text("Impact: " + ids.size + " shot(s) — " + shots.filter { it.id in ids }.joinToString { data(it).optString("code") } +
                        ". " + approvedDiagrams + " approved shot diagram(s) stay untouched.", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { editing = st }) { Text("Assign shots") }
                }
            }
        }
    }
    editing?.let { st ->
        val ids = data(st).optJSONArray("shotIds").ids()
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Shots in " + st.title) }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shots.forEach { s ->
                    val other = setups.firstOrNull { it.id != st.id && s.id in data(it).optJSONArray("shotIds").ids() }
                    Row { Checkbox(checked = s.id in ids, onCheckedChange = { on -> vm.act { vm.features.assignShotToSetup(st.id, s.id, on) } })
                        Text(shotLabel(s) + (other?.let { " (now in " + it.title + ")" } ?: "")) }
                }
            }
        }, confirmButton = { TextButton(onClick = { editing = null }) { Text("Done") } })
    }
}

/** #16: regenerate merges by stable key, never resetting statuses; optional items never block. */
@Composable
private fun ChecklistPanel(vm: StudioViewModel, project: StudioRecord, items: List<StudioRecord>) {
    val blocking = items.count { val b = data(it); !b.optBoolean("optional") && b.optString("status") in setOf("unresolved", "unavailable") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pre-shoot checklist · " + blocking + " required item(s) not ready", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { vm.act { val n = vm.features.regenerateChecklist(project.id); vm.report("$n new item(s); existing statuses kept") } }) {
            Text("Regenerate from shot plans")
        }
        items.sortedBy { data(it).optString("category") }.forEach { c ->
            val b = data(c)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(b.optString("category") + " · " + c.title, style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("unresolved", "ready", "unavailable", "na").forEach { st ->
                            FilterChip(selected = b.optString("status") == st, onClick = { vm.act { vm.features.setChecklistStatus(c.id, st) } }, label = { Text(st) })
                        }
                        FilterChip(selected = b.optBoolean("optional"), onClick = { vm.act { vm.features.setChecklistOptional(c.id, !b.optBoolean("optional")) } }, label = { Text("optional") })
                    }
                }
            }
        }
    }
}

/** #21: coverage dashboard; pickups become shots in the same scene; intentional omissions. */
@Composable
private fun CoveragePanel(vm: StudioViewModel, all: List<StudioRecord>, project: StudioRecord, scenes: List<StudioRecord>) {
    val cov = remember(all) { runCatching { ProductionLogic.coverage(ProjectData(all, project.id)) }.getOrNull() } ?: return
    var omitting by remember { mutableStateOf<JSONObject?>(null) }
    var pickupScene by remember { mutableStateOf<StudioRecord?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Coverage", style = MaterialTheme.typography.titleMedium)
        Text(cov.recorded.toString() + " recorded · " + cov.usable + " usable · " + cov.preferred + " preferred of " + cov.planned + " planned · " + cov.pickups.size + " pickups")
        cov.missing.forEach { s ->
            Row { Text("Missing: " + s.optString("code") + " " + s.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { omitting = s }) { Text("Omit…") } }
        }
        cov.missingRoomTone.forEach { Text("Room tone still needed for " + it.optString("code"), style = MaterialTheme.typography.bodySmall) }
        cov.unresolvedContinuity.forEach { Text("Open continuity: " + it.optString("aspect") + " — " + it.optString("observed").take(80), style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            scenes.forEach { sc -> TextButton(onClick = { pickupScene = sc }) { Text("+ Pickup in scene " + data(sc).optInt("number")) } }
        }
        OutlinedButton(enabled = vm.session != null && !vm.aiBusy && scenes.isNotEmpty(), onClick = {
            val text = (cov.missing.map { "MISSING id=" + it.optString("id") + " " + it.optString("code") + " " + it.optString("title") } +
                cov.missingRoomTone.map { "ROOM TONE " + it.optString("code") }).joinToString("\n").ifBlank { "No gaps recorded" }
            val targets = JSONArray().put(JSONObject().put("kind", "passage").put("id", project.id).put("label", "Coverage gaps").put("text", text))
            scenes.forEach { sc -> targets.put(JSONObject().put("kind", "scene").put("id", sc.id).put("label", sc.title).put("text", data(sc).optString("purpose"))) }
            vm.aiCreate(project.id, "production", "", targets, JSONArray(), JSONObject().put("task", "pickups")) { r ->
                val items = r.optJSONArray("items") ?: JSONArray()
                var saved = 0
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    val scene = it.optString("sceneId").takeIf { id -> scenes.any { s -> s.id == id } } ?: continue
                    vm.features.saveShotProposals(project.id, scene, null, listOf(JSONObject().put("title", it.optString("title"))
                        .put("purpose", it.optString("detail")).put("pickup", true).put("basis", it.optString("basis"))), "ai:claude-haiku-4-5")
                    saved++
                }
                "$saved pickup proposal(s) waiting in Story → Shot proposals (no video was analysed)"
            }
        }) { Text("AI pickup suggestions") }
    }
    omitting?.let { s ->
        var reason by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { omitting = null }, title = { Text("Omit " + s.optString("code")) }, text = {
            OutlinedTextField(reason, { reason = it }, label = { Text("Why is it intentionally omitted?") })
        }, confirmButton = { TextButton(enabled = reason.isNotBlank(), onClick = { vm.act { vm.features.omitShot(s.optString("id"), reason) }; omitting = null }) { Text("Omit") } },
            dismissButton = { TextButton(onClick = { omitting = null }) { Text("Cancel") } })
    }
    pickupScene?.let { sc ->
        var title by remember { mutableStateOf("") }; var why by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pickupScene = null }, title = { Text("Pickup shot") }, text = {
            Column { OutlinedTextField(title, { title = it }, label = { Text("Shot title") }); OutlinedTextField(why, { why = it }, label = { Text("Why it is needed") }) }
        }, confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { vm.act { vm.features.addPickup(sc.id, title, why) }; pickupScene = null }) { Text("Add to scene") } },
            dismissButton = { TextButton(onClick = { pickupScene = null }) { Text("Cancel") } })
    }
}

@Composable
private fun WrapSummary(vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, records: List<StudioRecord>,
                        all: List<StudioRecord>, onExport: (String) -> Unit, onImportFootage: () -> Unit) {
    val mediaRows = records.filter { it.collection == "media" }
    val externalRows = records.filter { it.collection == ExternalCameraNaming.COLLECTION }
    val pendingExternal = externalRows.count { !data(it).optBoolean("footageImported") }
    val pkg = remember(all) { runCatching { ProductionPackage.build(all, project.id, java.time.Instant.now().toString()) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Production wrap", style = MaterialTheme.typography.headlineMedium)
        Button(onClick = onImportFootage, enabled = !vm.importing && externalRows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.importing) "Hashing footage…" else "Import external camera footage ($pendingExternal waiting)")
        }
        Text("Pick the real files from the SD card / USB reader. Only files whose names were logged are imported, each read in full and SHA-256 verified.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { vm.verifyRecordedMedia(project.id) }, enabled = !vm.verifying && mediaRows.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
            Text(if (vm.verifying) "Verifying SHA-256…" else "Verify actual recordings by SHA-256")
        }
        Text("Export production package (metadata only)", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { onExport("json") }, modifier = Modifier.fillMaxWidth()) { Text("Export production metadata JSON") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onExport("md") }, modifier = Modifier.weight(1f)) { Text("Markdown") }
            OutlinedButton(onClick = { onExport("csv") }, modifier = Modifier.weight(1f)) { Text("CSV") }
            OutlinedButton(onClick = { onExport("zip") }, modifier = Modifier.weight(1f)) { Text("ZIP") }
        }
        Text(shots.count { data(it).optString("status") == "complete" }.toString() + " of " + shots.size + " planned shots marked complete")
        Text(records.count { it.collection == "takes" }.toString() + " actual recorded takes")
        Text(mediaRows.size.toString() + " media records · " + mediaRows.count { data(it).optString("verification") != "verified" } + " not checksum-verified")
        Text(externalRows.size.toString() + " external camera filenames logged · $pendingExternal not yet imported")
        HorizontalDivider()
        pkg?.getJSONObject("readiness")?.let { r ->
            listOf("Pre-production complete" to "preProductionComplete", "Production records complete" to "productionRecordsComplete",
                "Actual media verified" to "actualMediaVerified", "Ready for editorial handoff" to "readyForEditorialHandoff").forEach { (label, key) ->
                val x = r.getJSONObject(key)
                Text((if (x.getBoolean("ok")) "✓ " else "✗ ") + label, style = MaterialTheme.typography.titleSmall)
                val reasons = x.getJSONArray("reasons")
                for (i in 0 until minOf(3, reasons.length())) Text("   " + reasons.getString(i), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
