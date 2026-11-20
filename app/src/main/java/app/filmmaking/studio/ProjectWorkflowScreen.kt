package app.filmmaking.studio

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
import androidx.compose.ui.Modifier
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
private fun filmSources(project: StudioRecord): JSONArray {
    val p = data(project)
    val text = listOf(p.optString("name"), p.optString("concept"), p.optString("logline"), p.optString("mood"))
        .filter { it.isNotBlank() }.joinToString("\n").ifBlank { project.title }
    return JSONArray().put(JSONObject().put("layer", "C").put("label", "Current film").put("text", text.take(8000)))
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
    onShootMode: () -> Unit
) {
    var section by rememberSaveable(project.id) { mutableStateOf("Develop") }
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
            sections.forEach { tab -> Tab(selected = section == tab, onClick = { section = tab }, text = { Text(tab) }) }
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
                    item { StoryboardPanel(vm, project, shots, of("storyboards"), onPickStoryboard) }
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
                    WorkflowCard(vm, item, kind, shots) {
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
private fun WorkflowCard(vm: StudioViewModel, item: StudioRecord, kind: WorkflowKind, shots: List<StudioRecord>, edit: () -> Unit) {
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

/** #6/#7: reorder swaps order only; shot links survive; AI shots become pending proposals. */
@Composable
private fun BeatBoard(vm: StudioViewModel, project: StudioRecord, beats: List<StudioRecord>, shots: List<StudioRecord>, scenes: List<StudioRecord>) {
    var linking by remember { mutableStateOf<StudioRecord?>(null) }
    val sorted = beats.sortedBy { data(it).optDouble("order") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Beat board (" + sorted.size + ")", style = MaterialTheme.typography.titleMedium)
        if (sorted.isEmpty()) Text("Add visual beats below or accept AI beats above.", style = MaterialTheme.typography.bodySmall)
        sorted.forEachIndexed { i, beat ->
            val b = data(beat); val linked = b.optJSONArray("shotIds").ids()
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text((i + 1).toString() + ". " + beat.title, style = MaterialTheme.typography.titleSmall)
                    Text("Linked shots: " + (shots.filter { it.id in linked }.joinToString { data(it).optString("code") }.ifBlank { "none" }),
                        style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(enabled = i > 0, onClick = { vm.act { vm.features.moveBeat(project.id, beat.id, true) } }) { Text("↑ Up") }
                        TextButton(enabled = i < sorted.lastIndex, onClick = { vm.act { vm.features.moveBeat(project.id, beat.id, false) } }) { Text("↓ Down") }
                        TextButton(onClick = { linking = beat }) { Text("Link shots") }
                        val scene = shots.firstOrNull { it.id in linked }?.let { data(it).optString("sceneId") } ?: scenes.firstOrNull()?.id
                        TextButton(enabled = vm.session != null && !vm.aiBusy && scene != null, onClick = {
                            vm.aiCreate(project.id, "shots", "", JSONArray().put(JSONObject().put("kind", "beat").put("id", beat.id)
                                .put("label", beat.title).put("text", listOf(b.optString("intention"), b.optString("visual"), b.optString("passage")).joinToString("\n"))),
                                filmSources(project), count = 3) { r ->
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

/** #9: images are downscaled to ≤960 px; one chosen frame per shot. */
@Composable
private fun StoryboardPanel(vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, frames: List<StudioRecord>, pick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Storyboards", style = MaterialTheme.typography.titleMedium)
        if (shots.isEmpty()) Text("Add shots first.", style = MaterialTheme.typography.bodySmall)
        shots.forEach { s ->
            val mine = frames.filter { data(it).optString("shotId") == s.id }.sortedBy { data(it).optInt("version") }
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(shotLabel(s) + " · " + mine.size + " frame(s)", style = MaterialTheme.typography.titleSmall)
                    mine.forEach { f ->
                        val fb = data(f)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("v" + fb.optInt("version") + " · " + fb.optString("source") + (if (fb.optBoolean("conceptual")) " · conceptual" else "") +
                                if (fb.optBoolean("chosen")) " · ✓ chosen" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (!fb.optBoolean("chosen")) TextButton(onClick = { vm.act { vm.features.chooseStoryboard(f.id) } }) { Text("Choose") }
                        }
                    }
                    TextButton(onClick = { pick(s.id) }) { Text("Add frame from photos") }
                }
            }
        }
    }
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
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(b.optString("name") + " v" + b.optInt("version") + " · " + b.optString("status"), style = MaterialTheme.typography.titleSmall)
                    for (i in 0 until els.length()) {
                        val e = els.getJSONObject(i)
                        Row {
                            Text(e.optString("type") + " “" + e.optString("label") + "” at " + e.optDouble("x") + "m, " + e.optDouble("y") + "m",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { vm.act { val to = vm.features.removeDiagramElement(d.id, e.optString("id")); if (to != d.id) vm.report("Approved diagram kept; edit saved as a new draft version") } }) { Text("×") }
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
                                .put("label", b.optString("name")).put("text", text.ifBlank { "(empty diagram)" })), filmSources(project)) { r ->
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
                            vm.aiCreate(project.id, "schedule", "", JSONArray().put(JSONObject().put("kind", "day").put("id", day.id).put("label", day.title).put("text", text)), filmSources(project)) { r ->
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
