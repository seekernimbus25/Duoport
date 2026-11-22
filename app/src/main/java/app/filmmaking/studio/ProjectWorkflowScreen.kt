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
import app.filmmaking.studio.ui.*
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
import androidx.compose.ui.unit.sp
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
    onPickReference: () -> Unit,
    onShootMode: () -> Unit,
    section: String,
    onSection: (String) -> Unit,
    showTabs: Boolean = true
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
        if (showTabs) ScrollableTabRow(selectedTabIndex = sections.indexOf(section).coerceAtLeast(0), edgePadding = 8.dp) {
            sections.forEach { tab -> Tab(selected = section == tab, onClick = { onSection(tab) }, text = { Text(tab) }) }
        }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp)) {
            when (section) {
                "Develop" -> item { AiProposals(vm, project, "treatments") }
                "Story" -> {
                    item { AiProposals(vm, project, "beats") }
                    item { BeatBoard(vm, project, of("beats"), shots, scenes) }
                    item { ShotProposalPanel(vm, of("shotProposals")) }
                }
                "Visual" -> {
                    item { ReferenceBoardPanel(vm, project, shots, of("references").count { data(it).optString("image").startsWith("data:image/") }, onPickReference) }
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
                        StudioButton("Enter Shoot Mode (full screen, works offline)", onShootMode, Modifier.fillMaxWidth().heightIn(min = 64.dp), icon = app.filmmaking.studio.ui.Lucide.Camera)
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
                            StudioButton("+ " + kind.label, {
                                creating = kind; editing = null; newTitle = ""; newDetail = ""; shotId = shots.firstOrNull()?.id
                            }, variant = BtnVariant.Outline)
                        }
                    }
                }
                val ordered = kinds.flatMap { kind -> records.filter { it.collection == kind.collection }.sortedByDescending { it.updatedAt } }
                if (ordered.isEmpty()) item {
                    MutedText("No " + section.lowercase() + " notes yet. Create one above to begin.")
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
        StudioAlertDialog(
            onDismissRequest = { creating = null; editing = null },
            title = (if (editing == null) "Add " else "Edit ") + kind.label,
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioField(if (kind == WorkflowKind.CONTINUITY) "Planned condition" else "Title", newTitle, { newTitle = it }, Modifier.fillMaxWidth())
                    StudioField(kind.detailLabel, newDetail, { newDetail = it }, Modifier.fillMaxWidth(), minLines = 4)
                    if (kind == WorkflowKind.CONTINUITY && editing == null) {
                        Box {
                            StudioButton("Shot: " + (shots.find { it.id == shotId }?.let(::shotLabel) ?: "Select shot"), { shotDropdown = true }, variant = BtnVariant.Outline)
                            StudioMenu(shotDropdown, { shotDropdown = false }) {
                                shots.forEach { s -> StudioMenuItem(shotLabel(s), onClick = { shotId = s.id; shotDropdown = false }, selected = s.id == shotId) }
                            }
                        }
                    }
                    if (kind == WorkflowKind.CONTINUITY && editing != null)
                        Text("Corrections keep the original observation in the history.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    if (kind == WorkflowKind.SCRIPT)
                        Text("Approving saves an immutable version. Later edits only change the working draft.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                }
            },
            confirmButton = {
                StudioButton("Save offline", {
                    val item = editing
                    if (item == null) vm.createWorkflow(project.id, kind, newTitle.trim(), newDetail, shotId)
                    else vm.updateWorkflow(item.collection, item.id, newTitle.trim(), newDetail)
                    creating = null; editing = null
                }, enabled = newTitle.isNotBlank() && (kind != WorkflowKind.CONTINUITY || editing != null || shotId != null))
            },
            dismissButton = { StudioButton("Cancel", { creating = null; editing = null }, variant = BtnVariant.Ghost) }
        )
    }
}

@Composable
private fun AiProposals(vm: StudioViewModel, project: StudioRecord, op: String) {
    var instructions by rememberSaveable(project.id + op) { mutableStateOf("") }
    val generated = vm.generatedOptions?.takeIf { it.projectId == project.id && it.op == op }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        StudioField("Optional direction for AI proposals", instructions, { instructions = it }, Modifier.fillMaxWidth(), singleLine = false)
        StudioButton(if (vm.generating) "Generating…" else "Suggest " + if (op == "treatments") "2 treatments" else "2 visual beats", { vm.generateOptions(project, op, instructions) }, variant = BtnVariant.Outline, enabled = vm.session != null && !vm.generating)
        if (vm.session == null) Text("Sign in via Settings to generate AI proposals.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        if (generated != null) {
            Text("AI suggestions — only accepted items will be saved", style = MaterialTheme.typography.labelLarge)
            generated.estimatedCostUsd?.let { Text("Estimated model cost: $" + String.format("%.4f", it), style = EyebrowStyle.copy(letterSpacing = 0.sp)) }
            generated.items.forEachIndexed { index, idea ->
                StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(idea.optString("title"), style = MaterialTheme.typography.titleLarge)
                        Text(if (op == "treatments") idea.optString("premise") else idea.optString("visual"))
                        StudioButton("Accept as editable draft", { vm.acceptGenerated(index) }, variant = BtnVariant.Ghost)
                    }
                }
            }
            StudioButton("Discard remaining AI suggestions", vm::clearGenerated, variant = BtnVariant.Ghost)
        }
    }
}

@Composable
private fun WorkflowCard(vm: StudioViewModel, item: StudioRecord, kind: WorkflowKind, shots: List<StudioRecord>,
                         pickPhoto: (String, String) -> Unit = { _, _ -> }, edit: () -> Unit) {
    val json = data(item)
    var critiqueOpen by remember { mutableStateOf(false) }
    StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind.label, style = EyebrowStyle.copy(letterSpacing = 0.sp))
            Text(item.title, style = MaterialTheme.typography.titleLarge)
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
                            style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    }
                }
                WorkflowKind.CONTINUITY -> {
                    Text("Shot: " + (shots.find { it.id == json.optString("shotId") }?.let(::shotLabel) ?: "?") +
                        " · " + (json.optJSONArray("history")?.length() ?: 0) + " earlier observation(s)", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("record" to "On record", "speculative" to "Speculative").forEach { (b, label) ->
                            StudioChip(label, active = json.optString("basis") == b, onClick = { vm.act { vm.features.setContinuity(item.id, basis = b) } })
                        }
                        StudioChip(if (json.optBoolean("resolved")) "Resolved" else "Open", active = json.optBoolean("resolved"), onClick = {
                            vm.act { vm.features.setContinuity(item.id, resolved = !json.optBoolean("resolved")) }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("planned" to "Planned photo", "actual" to "Actual photo").forEach { (which, label) ->
                            val img = rememberDataImage(json.optString(which + "Image").ifBlank { null })
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (img != null) androidx.compose.foundation.Image(img, label, Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                                Text(label, style = EyebrowStyle.copy(letterSpacing = 0.sp))
                                Row {
                                    StudioButton(if (img == null) "Add" else "Replace", { pickPhoto(item.id, which) }, variant = BtnVariant.Ghost)
                                    if (img != null) StudioButton("Remove", { vm.act { vm.features.setContinuityPhoto(item.id, which, null) } }, variant = BtnVariant.Ghost)
                                }
                            }
                        }
                    }
                }
                WorkflowKind.REFERENCE -> ReferenceDetails(vm, item, json, shots)
                else -> Unit
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!(kind == WorkflowKind.TREATMENT && json.optString("status") == "approved")) StudioButton("Edit", edit, variant = BtnVariant.Ghost)
                if (kind == WorkflowKind.TREATMENT && json.optString("status") != "approved")
                    StudioButton("Approve", { vm.approveTreatment(json.optString("projectId"), item.id) }, variant = BtnVariant.Ghost)
                if (kind == WorkflowKind.SCRIPT) {
                    StudioButton("Approve draft version", { vm.approveScript(item.id) }, variant = BtnVariant.Ghost)
                    StudioButton("AI critique", { critiqueOpen = true }, variant = BtnVariant.Ghost, enabled = vm.session != null && !vm.aiBusy)
                }
                StudioButton("Delete", { vm.act { vm.features.removeRecord(item.collection, item.id) } }, variant = BtnVariant.Ghost)
            }
        }
    }
    if (critiqueOpen) CritiqueDialog(vm, item) { critiqueOpen = false }
}

/** The fetched picture itself, where it came from, and which shot it inspires. */
@Composable
private fun ReferenceDetails(vm: StudioViewModel, item: StudioRecord, json: JSONObject, shots: List<StudioRecord>) {
    val img = rememberDataImage(json.optString("image").takeIf { it.startsWith("data:image/") })
    if (img != null) androidx.compose.foundation.Image(img, item.title, Modifier.fillMaxWidth().heightIn(max = 420.dp),
        contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    val source = json.optString("sourceUrl").takeIf { it.isNotBlank() }
        ?.let { runCatching { android.net.Uri.parse(it).host?.removePrefix("www.") }.getOrNull() }
    val from = when (json.optString("addedFrom")) { "link" -> "From a link"; "clipboard" -> "Pasted"; "phone" -> "From this phone"; else -> null }
    listOfNotNull(from, source?.let { "source: $it" }).takeIf { it.isNotEmpty() }?.let {
        Text(it.joinToString(" · "), style = EyebrowStyle.copy(letterSpacing = 0.sp))
    }
    val attached = json.optJSONArray("attachedShotIds").ids().firstOrNull()
    var open by remember { mutableStateOf(false) }
    Box {
        StudioButton("Inspires: " + (shots.find { it.id == attached }?.let(::shotLabel) ?: "the whole film"), { open = true }, variant = BtnVariant.Outline)
        StudioMenu(open, { open = false }) {
            StudioMenuItem("The whole film (no shot)", onClick = { open = false; vm.attachReference(item.id, null) }, selected = attached == null)
            shots.forEach { s -> StudioMenuItem(shotLabel(s), onClick = { open = false; vm.attachReference(item.id, s.id) }, selected = s.id == attached) }
        }
    }
}

/**
 * Inspiration images on the board: paste a copied image or link, fetch a link (the phone downloads the picture
 * itself and keeps a copy), or pick one from the phone. Each waits in a preview until it is named and saved.
 */
@Composable
private fun ReferenceBoardPanel(vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, imageCount: Int, pickFromPhone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var linkOpen by remember { mutableStateOf(false) }
    var link by remember { mutableStateOf("") }
    StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Reference board", style = MaterialTheme.typography.titleLarge)
            Text("Inspiration images for the look of the film: paste one you copied, fetch one from a link, or pick one from this phone. " +
                if (imageCount == 0) "None yet." else "$imageCount image(s) below.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioButton("Paste", { vm.referenceFromClipboard(context) }, enabled = !vm.referenceBusy)
                StudioButton("From a link", { link = ""; linkOpen = true }, variant = BtnVariant.Outline, enabled = !vm.referenceBusy)
                StudioButton("From this phone", pickFromPhone, variant = BtnVariant.Outline, enabled = !vm.referenceBusy)
            }
            if (vm.referenceBusy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioSpinner(); Text("Getting the image…", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
            }
        }
    }
    if (linkOpen) StudioAlertDialog(onDismissRequest = { linkOpen = false }, title = "Image from a link", text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioField("Link to an image or a web page", link, { link = it }, Modifier.fillMaxWidth())
            Text("The phone downloads the picture and keeps a copy on the board, so it still shows if the site changes. " +
                "For a web page, its preview image is used. It's an inspiration reference: check usage rights before you publish it.",
                style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        }
    }, confirmButton = {
        StudioButton("Fetch image", { linkOpen = false; vm.referenceFromLink(link) }, enabled = link.isNotBlank())
    }, dismissButton = { StudioButton("Cancel", { linkOpen = false }, variant = BtnVariant.Ghost) })

    val draft = vm.referenceDraft ?: return
    var title by remember(draft) { mutableStateOf(draft.title) }
    var notes by remember(draft) { mutableStateOf("") }
    var shotId by remember(draft) { mutableStateOf<String?>(null) }
    var shotMenu by remember { mutableStateOf(false) }
    StudioAlertDialog(onDismissRequest = vm::discardReference, title = "Add to the reference board", text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rememberDataImage(draft.image)?.let { androidx.compose.foundation.Image(it, "Reference preview", Modifier.fillMaxWidth().heightIn(max = 320.dp),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit) }
            draft.sourceUrl?.let { u -> runCatching { android.net.Uri.parse(u).host?.removePrefix("www.") }.getOrNull() }
                ?.let { Text("From $it", style = EyebrowStyle.copy(letterSpacing = 0.sp)) }
            StudioField("Title", title, { title = it }, Modifier.fillMaxWidth())
            StudioField("What you like about it (optional)", notes, { notes = it }, Modifier.fillMaxWidth(), minLines = 2)
            Box {
                StudioButton("Inspires: " + (shots.find { it.id == shotId }?.let(::shotLabel) ?: "the whole film"), { shotMenu = true }, variant = BtnVariant.Outline)
                StudioMenu(shotMenu, { shotMenu = false }) {
                    StudioMenuItem("The whole film (no shot)", onClick = { shotMenu = false; shotId = null }, selected = shotId == null)
                    shots.forEach { s -> StudioMenuItem(shotLabel(s), onClick = { shotMenu = false; shotId = s.id }, selected = s.id == shotId) }
                }
            }
        }
    }, confirmButton = {
        StudioButton("Save to board", { vm.saveReference(project.id, title, notes, shotId) })
    }, dismissButton = { StudioButton("Discard", vm::discardReference, variant = BtnVariant.Ghost) })
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
    StudioAlertDialog(onDismissRequest = close, title = "Script critique", text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = result
            if (r == null) Text(if (vm.aiBusy) "Asking the script doctor…" else "No critique yet")
            else {
                Text(r.optString("summary"))
                val items = r.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val it = items.getJSONObject(i)
                    Text("• [" + it.optString("kind") + "] “" + it.optString("original").take(120) + "” → " + it.optString("suggestion"),
                        style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                }
            }
        }
    }, confirmButton = {
        val revised = result?.optString("revisedDraft").orEmpty()
        StudioButton("Save revision as new draft", {
            vm.act { vm.features.addDraftVersion(artifact.id, revised, "AI critique revision", "assistant"); vm.report("Saved as a new DRAFT version; the approved text is unchanged") }
            close()
        }, enabled = revised.isNotBlank())
    }, dismissButton = { StudioButton("Close", close, variant = BtnVariant.Ghost) })
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
        Text("Beat board (" + sorted.size + ")", style = MaterialTheme.typography.titleLarge)
        if (sorted.isEmpty()) Text("Add visual beats below or accept AI beats above.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        else Text("Drag ≡ to reorder. Shot links stay with their beat.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        live.forEachIndexed { i, id ->
          // key(id): a card keeps its composition (and its in-flight drag gesture) when it changes slot.
          val beat = byId[id] ?: return@forEachIndexed
          key(id) {
            val b = data(beat); val linked = b.optJSONArray("shotIds").ids()
            val isDragged = dragging == id
            StudioCard(Modifier.fillMaxWidth().zIndex(if (isDragged) 1f else 0f)
                .graphicsLayer { translationY = if (isDragged) dragOffset else 0f; shadowElevation = if (isDragged) 12f else 0f }
                .onGloballyPositioned { heights[id] = it.size.height }, padding = 0.dp) {
                Column(Modifier.padding(14.dp)) {
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
                        style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        StudioButton("↑ Up", { vm.act { vm.features.moveBeat(project.id, beat.id, true) } }, variant = BtnVariant.Ghost, enabled = i > 0)
                        StudioButton("↓ Down", { vm.act { vm.features.moveBeat(project.id, beat.id, false) } }, variant = BtnVariant.Ghost, enabled = i < live.lastIndex)
                        StudioButton("Link shots", { linking = beat }, variant = BtnVariant.Ghost)
                        val scene = shots.firstOrNull { it.id in linked }?.let { data(it).optString("sceneId") } ?: scenes.firstOrNull()?.id
                        StudioButton("AI shot ideas", {
                            vm.aiCreate(project.id, "shots", "", JSONArray().put(JSONObject().put("kind", "beat").put("id", beat.id)
                                .put("label", beat.title).put("text", listOf(b.optString("intention"), b.optString("visual"), b.optString("passage")).joinToString("\n"))),
                                filmSources(project, vm.memoriesFor(project.id)), count = 3) { r ->
                                val arr = r.optJSONArray("shots") ?: JSONArray()
                                vm.features.saveShotProposals(project.id, scene!!, beat.id, (0 until arr.length()).map { arr.getJSONObject(it) }, "ai:claude-haiku-4-5")
                                arr.length().toString() + " shot proposal(s) waiting for review — nothing was added to the shot list"
                            }
                        }, variant = BtnVariant.Ghost, enabled = vm.session != null && !vm.aiBusy && scene != null)
                    }
                }
            }
          }
        }
    }
    linking?.let { beat ->
        var chosen by remember(beat.id) { mutableStateOf(data(beat).optJSONArray("shotIds").ids().toSet()) }
        StudioAlertDialog(onDismissRequest = { linking = null }, title = "Shots for " + beat.title, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shots.forEach { s ->
                    StudioCheckRow(shotLabel(s), s.id in chosen, { on -> chosen = if (on) chosen + s.id else chosen - s.id })
                }
            }
        }, confirmButton = { StudioButton("Save links", { vm.act { vm.features.linkBeatShots(beat.id, chosen.toList()) }; linking = null }) },
            dismissButton = { StudioButton("Cancel", { linking = null }, variant = BtnVariant.Ghost) })
    }
}

@Composable
private fun ShotProposalPanel(vm: StudioViewModel, proposals: List<StudioRecord>) {
    val pending = proposals.filter { data(it).optString("status") == "pending" }
    if (pending.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shot proposals awaiting review (" + pending.size + ")", style = MaterialTheme.typography.titleLarge)
        pending.forEach { p ->
            val b = data(p)
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleSmall)
                    Text(listOf(b.optString("shotSize"), b.optString("lens"), b.optString("movement")).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    if (b.optString("whyItWorks").isNotBlank()) Text(b.optString("whyItWorks"), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    Row {
                        StudioButton("Accept as shot", { vm.act { vm.features.decideShotProposal(p.id, true); vm.report("Shot added to the scene; it keeps its beat and proposal links") } }, variant = BtnVariant.Ghost)
                        StudioButton("Reject", { vm.act { vm.features.decideShotProposal(p.id, false) } }, variant = BtnVariant.Ghost)
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
        Text("Storyboards", style = MaterialTheme.typography.titleLarge)
        if (shots.isEmpty()) Text("Add shots first.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        shots.forEach { s ->
            val mine = frames.filter { data(it).optString("shotId") == s.id }.sortedByDescending { data(it).optInt("version") }
            val chosen = mine.firstOrNull { data(it).optBoolean("chosen") }
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(shotLabel(s) + " · " + mine.size + " frame(s)", style = MaterialTheme.typography.titleSmall)
                    if (chosen != null) {
                        FrameView(data(chosen), Modifier.fillMaxWidth())
                        AnnotationEditor(vm, chosen)
                    } else if (mine.isNotEmpty()) Text("No frame chosen yet — tap a version below.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    if (mine.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        mine.forEach { f ->
                            val fb = data(f)
                            Column(Modifier.width(132.dp)) {
                                FrameView(fb, Modifier.fillMaxWidth())
                                Text("v" + fb.optInt("version") + " · " + fb.optString("source") + if (fb.optBoolean("chosen")) " · ✓ chosen" else "",
                                    style = EyebrowStyle.copy(letterSpacing = 0.sp))
                                if (!fb.optBoolean("chosen")) StudioButton("Choose", { vm.act { vm.features.chooseStoryboard(f.id) } }, variant = BtnVariant.Ghost)
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        StudioButton("Add frame from photos", { pick(s.id) }, variant = BtnVariant.Ghost)
                        StudioButton("Sketch", { sketching = s }, variant = BtnVariant.Ghost)
                        StudioButton("From archive", { archiveFor = s }, variant = BtnVariant.Ghost, enabled = vm.session != null)
                        StudioButton("AI frame", { aiFor = s }, variant = BtnVariant.Ghost, enabled = vm.session != null && !vm.aiBusy)
                    }
                }
            }
        }
    }
    sketching?.let { shot ->
        val base = frames.firstOrNull { data(it).optString("shotId") == shot.id && data(it).optBoolean("chosen") }?.let { data(it).optString("imageUri").ifBlank { null } }
        StudioAlertDialog(onDismissRequest = { sketching = null }, title = "Sketch " + data(shot).optString("code"), text = {
            SketchPad(base, onSave = { strokes ->
                vm.act { vm.features.addStoryboard(project.id, shot.id, base, "drawing", JSONObject(), false, strokes = strokes); vm.report("Sketch saved as a new storyboard version") }
                sketching = null
            }, onCancel = { sketching = null })
        }, confirmButton = {})
    }
    archiveFor?.let { shot ->
        var q by remember(shot.id) { mutableStateOf("") }
        StudioAlertDialog(onDismissRequest = { archiveFor = null }, title = "Reference from your archive", text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioField("e.g. steam, window light", q, { q = it }, Modifier.fillMaxWidth())
                StudioButton("Search", { vm.searchArchive(q) }, enabled = q.isNotBlank() && !vm.aiBusy)
                vm.archiveResults.forEach { h ->
                    StudioCard(Modifier.fillMaxWidth(), onClick = {
                        vm.act { vm.features.addStoryboard(project.id, shot.id, null, "archive", JSONObject(), false,
                            archiveRef = JSONObject().put("documentId", h.optString("document_id")).put("title", h.optString("title")).put("excerpt", h.optString("content").take(600)))
                            vm.report("Archive passage attached as a storyboard reference") }
                        archiveFor = null
                    }, padding = 0.dp) { Text(h.optString("title") + " — " + h.optString("content").take(140) + "…", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground) }
                }
                Text("Your archive holds text, so this attaches a cited passage as the reference.", style = EyebrowStyle.copy(letterSpacing = 0.sp))
            }
        }, confirmButton = {}, dismissButton = { StudioButton("Close", { archiveFor = null }, variant = BtnVariant.Ghost) })
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
        StudioAlertDialog(onDismissRequest = { aiFor = null }, title = "Generate a conceptual frame?", text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioField("Your idea (a few words is fine)", idea, { idea = it }, Modifier.fillMaxWidth(), placeholder = shot.title, singleLine = false)
                Text("Brief sent to the image model, built from this shot's plan:", style = MaterialTheme.typography.labelMedium)
                if (editing) {
                    StudioField("Edited brief", edited, { edited = it.take(StoryboardBrief.MAX_CHARS) }, Modifier.fillMaxWidth(), singleLine = false)
                    StudioButton("Rebuild from the shot plan", { editing = false }, variant = BtnVariant.Ghost)
                } else {
                    StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            brief.lines.forEach { l -> Text(l.label + " — " + l.text, style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground) }
                        }
                    }
                    StudioButton("Edit the brief", { edited = brief.text; editing = true }, variant = BtnVariant.Ghost)
                }
                Text(prompt.length.toString() + " / " + StoryboardBrief.MAX_CHARS + " characters · " + brief.orientation.label + " frame" +
                    " · the server adds a rough pencil-and-wash storyboard style.", style = EyebrowStyle.copy(letterSpacing = 0.sp))
                Text("One image with OpenAI (gpt-image-1, low quality). Estimated cost: $" + cost + ", counted against your daily AI limit. " +
                    "AI frames are conceptual references, not technically accurate. Fill in the shot's camera and lighting plan for a closer frame.",
                    style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
            }
        }, confirmButton = {
            StudioButton("Generate (≈ $" + cost + ")", {
                vm.generateStoryboardImage(project.id, shot.id, prompt, brief.orientation.wire); aiFor = null
            }, enabled = prompt.trim().length >= 5)
        }, dismissButton = { StudioButton("Cancel", { aiFor = null }, variant = BtnVariant.Ghost) })
    }
}

@Composable
private fun AnnotationEditor(vm: StudioViewModel, frame: StudioRecord) {
    val ann = data(frame).optJSONObject("annotations") ?: JSONObject()
    val values = remember(frame.id, ann.toString()) { mutableStateMapOf<String, String>().apply { FeatureRepository.ANNOTATION_KEYS.forEach { put(it, ann.optString(it)) } } }
    val labels = mapOf("composition" to "Composition", "movement" to "Movement", "lighting" to "Lighting", "intent" to "Visual intent")
    FeatureRepository.ANNOTATION_KEYS.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pair.forEach { k -> StudioField(labels.getValue(k), values[k].orEmpty(), { values[k] = it }, Modifier.weight(1f), singleLine = false) }
        }
    }
    val changed = FeatureRepository.ANNOTATION_KEYS.any { values[it].orEmpty() != ann.optString(it) }
    if (changed) StudioButton("Save frame notes", { vm.act { vm.features.setStoryboardAnnotations(frame.id, values.toMap()) } }, variant = BtnVariant.Ghost)
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
        Text("Setup diagrams (metres)", style = MaterialTheme.typography.titleLarge)
        if (scenes.isEmpty()) { Text("Add a scene first.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground); return@Column }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            scenes.forEach { sc -> StudioChip("Scene " + data(sc).optInt("number"), active = sceneId == sc.id, onClick = { sceneId = sc.id }) }
        }
        StudioField("New diagram name", name, { name = it }, Modifier.fillMaxWidth())
        StudioButton("Create diagram", { vm.act { vm.features.createDiagram(project.id, sceneId!!, null, name) }; name = "" }, enabled = name.isNotBlank() && sceneId != null)
        recipes.forEach { r ->
            StudioButton("Apply lookbook recipe: " + r.title, { vm.act { vm.features.applyRecipe(r.id, project.id, sceneId!!, null); vm.report("Recipe copied into a new diagram; the recipe itself is unchanged") } }, variant = BtnVariant.Ghost, enabled = sceneId != null)
        }
        diagrams.sortedBy { data(it).optString("name") + data(it).optInt("version") }.forEach { d ->
            val b = data(d); val els = b.optJSONArray("elements") ?: JSONArray()
            var selected by remember(d.id) { mutableStateOf<String?>(null) }
            val fork: (String) -> Unit = { to -> if (to != d.id) vm.report("Approved diagram kept; edit saved as a new draft version") }
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(b.optString("name") + " v" + b.optInt("version") + " · " + b.optString("status") + " · " +
                        b.optDouble("width", 10.0).toInt() + "×" + b.optDouble("height", 8.0).toInt() + " m", style = MaterialTheme.typography.titleSmall)
                    DiagramCanvas(b, selected, { selected = it }) { id, x, y -> vm.act { fork(vm.features.moveDiagramElement(d.id, id, x, y)) } }
                    Text("Tap to select, drag to move (1 m grid).", style = EyebrowStyle.copy(letterSpacing = 0.sp))
                    val sel = (0 until els.length()).map { els.getJSONObject(it) }.firstOrNull { it.optString("id") == selected }
                    if (sel != null) {
                        val sid = sel.optString("id")
                        Text("Selected: " + sel.optString("type") + " “" + sel.optString("label") + "” at " + sel.optDouble("x") + " m, " +
                            sel.optDouble("y") + " m, " + sel.optDouble("rotation", 0.0).toInt() + "°", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            StudioButton("⟲ 15°", { vm.act { fork(vm.features.rotateDiagramElement(d.id, sid, -15.0)) } }, variant = BtnVariant.Ghost)
                            StudioButton("⟳ 15°", { vm.act { fork(vm.features.rotateDiagramElement(d.id, sid, 15.0)) } }, variant = BtnVariant.Ghost)
                            listOf("←" to (-0.5 to 0.0), "→" to (0.5 to 0.0), "↑" to (0.0 to -0.5), "↓" to (0.0 to 0.5)).forEach { (arrow, delta) ->
                                StudioButton(arrow, { vm.act { fork(vm.features.moveDiagramElement(d.id, sid, sel.optDouble("x") + delta.first, sel.optDouble("y") + delta.second)) } }, variant = BtnVariant.Ghost)
                            }
                            StudioButton("Delete element", { vm.act { fork(vm.features.removeDiagramElement(d.id, sid)) }; selected = null }, variant = BtnVariant.Ghost)
                        }
                    }
                    for (i in 0 until els.length()) {
                        val e = els.getJSONObject(i)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(e.optString("type") + " “" + e.optString("label") + "” at " + e.optDouble("x") + "m, " + e.optDouble("y") + "m",
                                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                            StudioButton("×", { vm.act { fork(vm.features.removeDiagramElement(d.id, e.optString("id"))) } }, variant = BtnVariant.Ghost)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        StudioButton("+ Element", { adding = d }, variant = BtnVariant.Ghost)
                        if (b.optString("status") != "approved") StudioButton("Approve", { vm.act { vm.features.approveDiagram(d.id) } }, variant = BtnVariant.Ghost)
                        StudioButton("Save as recipe", { vm.act { vm.features.saveDiagramAsRecipe(d.id, b.optString("name") + " recipe") } }, variant = BtnVariant.Ghost)
                        StudioButton("AI cinematographer", {
                            val text = (0 until els.length()).joinToString("\n") { i -> val e = els.getJSONObject(i)
                                "id=" + e.optString("id") + " " + e.optString("type") + " \"" + e.optString("label") + "\" x=" + e.optDouble("x") + " y=" + e.optDouble("y") }
                            vm.aiCreate(project.id, "setup", "", JSONArray().put(JSONObject().put("kind", "diagram").put("id", d.id)
                                .put("label", b.optString("name")).put("text", text.ifBlank { "(empty diagram)" })), filmSources(project, vm.memoriesFor(project.id))) { r ->
                                preview = d to vm.features.previewSetupChanges(b, r.optJSONArray("changes") ?: JSONArray())
                                "AI setup suggestion ready: preview before accepting. " + r.optString("explanation").take(160)
                            }
                        }, variant = BtnVariant.Ghost, enabled = vm.session != null && !vm.aiBusy)
                    }
                }
            }
        }
    }
    adding?.let { d ->
        var type by remember { mutableStateOf("camera") }
        var x by remember { mutableStateOf("5") }; var y by remember { mutableStateOf("4") }; var label by remember { mutableStateOf("") }
        var open by remember { mutableStateOf(false) }
        StudioAlertDialog(onDismissRequest = { adding = null }, title = "Add element", text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box { StudioButton("Type: $type", { open = true }, variant = BtnVariant.Outline)
                    StudioMenu(open, { open = false }) { FeatureRepository.DIAGRAM_TYPES.forEach { t -> StudioMenuItem(t, onClick = { type = t; open = false }, selected = t == type) } } }
                StudioField("x (metres from left)", x, { x = it })
                StudioField("y (metres from top)", y, { y = it })
                StudioField("Label", label, { label = it })
            }
        }, confirmButton = {
            StudioButton("Add", {
                vm.act { val to = vm.features.addDiagramElement(d.id, type, x.toDouble(), y.toDouble(), label)
                    if (to != d.id) vm.report("Approved diagram kept; edit saved as a new draft version") }
                adding = null
            }, enabled = x.toDoubleOrNull() != null && y.toDoubleOrNull() != null)
        }, dismissButton = { StudioButton("Cancel", { adding = null }, variant = BtnVariant.Ghost) })
    }
    preview?.let { (d, els) ->
        StudioAlertDialog(onDismissRequest = { preview = null }, title = "Preview AI setup", text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DiagramCanvas(JSONObject(data(d).toString()).put("elements", els), null, {}) { _, _, _ -> }
                for (i in 0 until els.length()) { val e = els.getJSONObject(i)
                    Text(e.optString("type") + " “" + e.optString("label") + "” " + e.optDouble("x") + "m, " + e.optDouble("y") + "m", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground) }
            }
        }, confirmButton = { StudioButton("Accept changes", { vm.act { vm.features.acceptSetupChanges(d.id, els) }; preview = null }) },
            dismissButton = { StudioButton("Discard", { preview = null }, variant = BtnVariant.Ghost) })
    }
}

/** #13/#14: day order editing + approved history; AI schedule only applied on Approve. */
@Composable
private fun DayPlanner(vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, days: List<StudioRecord>, proposals: List<StudioRecord>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shooting order", style = MaterialTheme.typography.titleLarge)
        if (days.isEmpty()) Text("Add a shoot day below, then build its order.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        days.forEach { day ->
            val b = data(day)
            val approved = b.optJSONArray("order").ids()
            var draft by remember(day.id, day.updatedAt) { mutableStateOf(b.optJSONArray("draftOrder")?.ids() ?: approved) }
            var addOpen by remember { mutableStateOf(false) }
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text(day.title + " · " + approved.size + " approved · " + (b.optJSONArray("history")?.length() ?: 0) + " earlier order(s)",
                        style = MaterialTheme.typography.titleSmall)
                    draft.forEachIndexed { i, id ->
                        val s = shots.find { it.id == id } ?: return@forEachIndexed
                        Row {
                            Text((i + 1).toString() + ". " + shotLabel(s), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                            StudioButton("↑", { draft = draft.toMutableList().also { java.util.Collections.swap(it, i, i - 1) } }, variant = BtnVariant.Ghost, enabled = i > 0)
                            StudioButton("×", { draft = draft - id }, variant = BtnVariant.Ghost)
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        Box { StudioButton("+ Shot", { addOpen = true }, variant = BtnVariant.Ghost)
                            StudioMenu(addOpen, { addOpen = false }) {
                                shots.filter { it.id !in draft }.forEach { s -> StudioMenuItem(shotLabel(s), onClick = { draft = draft + s.id; addOpen = false }) }
                            } }
                        StudioButton("Approve order", {
                            vm.act { vm.features.approveDayOrder(day.id, draft, "Edited by hand"); vm.report("Shooting order approved; narrative order unchanged") }
                        }, variant = BtnVariant.Ghost, enabled = draft != approved && draft.isNotEmpty())
                        StudioButton("AI schedule", {
                            val text = shots.joinToString("\n") { "id=" + it.id + " " + shotLabel(it) + " setup " + (data(it).optJSONObject("production")?.optString("setup") ?: "") }
                            vm.aiCreate(project.id, "schedule", "", JSONArray().put(JSONObject().put("kind", "day").put("id", day.id).put("label", day.title).put("text", text)), filmSources(project, vm.memoriesFor(project.id))) { r ->
                                vm.features.saveScheduleProposal(project.id, day.id, r, "ai:claude-haiku-4-5"); "AI schedule proposal saved as pending — approve it to apply"
                            }
                        }, variant = BtnVariant.Ghost, enabled = vm.session != null && !vm.aiBusy && shots.isNotEmpty())
                    }
                }
            }
        }
        proposals.filter { data(it).optString("status") == "pending" }.forEach { p ->
            val b = data(p)
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text("Pending schedule · ~" + b.optInt("estimatedMin") + " min (estimate)", style = MaterialTheme.typography.titleSmall)
                    Text(b.optJSONArray("order").ids().mapNotNull { id -> shots.find { it.id == id }?.let { data(it).optString("code") } }.joinToString(" → "))
                    Text(b.optString("reasoning").take(300), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    Row {
                        StudioButton("Approve", { vm.act { vm.features.decideScheduleProposal(p.id, true) } }, variant = BtnVariant.Ghost)
                        StudioButton("Reject", { vm.act { vm.features.decideScheduleProposal(p.id, false) } }, variant = BtnVariant.Ghost)
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
        Text("Setups and impact", style = MaterialTheme.typography.titleLarge)
        setups.forEach { st ->
            val ids = data(st).optJSONArray("shotIds").ids()
            val approvedDiagrams = diagrams.count { data(it).optString("shotId") in ids && data(it).optString("status") == "approved" }
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text(st.title, style = MaterialTheme.typography.titleSmall)
                    Text("Impact: " + ids.size + " shot(s) — " + shots.filter { it.id in ids }.joinToString { data(it).optString("code") } +
                        ". " + approvedDiagrams + " approved shot diagram(s) stay untouched.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    StudioButton("Assign shots", { editing = st }, variant = BtnVariant.Ghost)
                }
            }
        }
    }
    editing?.let { st ->
        val ids = data(st).optJSONArray("shotIds").ids()
        StudioAlertDialog(onDismissRequest = { editing = null }, title = "Shots in " + st.title, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                shots.forEach { s ->
                    val other = setups.firstOrNull { it.id != st.id && s.id in data(it).optJSONArray("shotIds").ids() }
                    StudioCheckRow(shotLabel(s), s.id in ids, { on -> vm.act { vm.features.assignShotToSetup(st.id, s.id, on) } },
                        body = other?.let { "Now in " + it.title })
                }
            }
        }, confirmButton = { StudioButton("Done", { editing = null }) })
    }
}

/** #16: regenerate merges by stable key, never resetting statuses; optional items never block. */
@Composable
private fun ChecklistPanel(vm: StudioViewModel, project: StudioRecord, items: List<StudioRecord>) {
    val blocking = items.count { val b = data(it); !b.optBoolean("optional") && b.optString("status") in setOf("unresolved", "unavailable") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pre-shoot checklist · " + blocking + " required item(s) not ready", style = MaterialTheme.typography.titleLarge)
        StudioButton("Regenerate from shot plans", { vm.act { val n = vm.features.regenerateChecklist(project.id); vm.report("$n new item(s); existing statuses kept") } }, variant = BtnVariant.Outline)
        items.sortedBy { data(it).optString("category") }.forEach { c ->
            val b = data(c)
            StudioCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(14.dp)) {
                    Text(b.optString("category") + " · " + c.title, style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("unresolved", "ready", "unavailable", "na").forEach { st ->
                            StudioChip(st, active = b.optString("status") == st, onClick = { vm.act { vm.features.setChecklistStatus(c.id, st) } })
                        }
                        StudioChip("optional", active = b.optBoolean("optional"), onClick = { vm.act { vm.features.setChecklistOptional(c.id, !b.optBoolean("optional")) } })
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
        Text("Coverage", style = MaterialTheme.typography.titleLarge)
        Text(cov.recorded.toString() + " recorded · " + cov.usable + " usable · " + cov.preferred + " preferred of " + cov.planned + " planned · " + cov.pickups.size + " pickups")
        cov.missing.forEach { s ->
            Row { Text("Missing: " + s.optString("code") + " " + s.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                StudioButton("Omit…", { omitting = s }, variant = BtnVariant.Ghost) }
        }
        cov.missingRoomTone.forEach { Text("Room tone still needed for " + it.optString("code"), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground) }
        cov.unresolvedContinuity.forEach { Text("Open continuity: " + it.optString("aspect") + " — " + it.optString("observed").take(80), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground) }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            scenes.forEach { sc -> StudioButton("+ Pickup in scene " + data(sc).optInt("number"), { pickupScene = sc }, variant = BtnVariant.Ghost) }
        }
        StudioButton("AI pickup suggestions", {
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
        }, variant = BtnVariant.Outline, enabled = vm.session != null && !vm.aiBusy && scenes.isNotEmpty())
    }
    omitting?.let { s ->
        var reason by remember { mutableStateOf("") }
        StudioAlertDialog(onDismissRequest = { omitting = null }, title = "Omit " + s.optString("code"), text = {
            StudioField("Why is it intentionally omitted?", reason, { reason = it })
        }, confirmButton = { StudioButton("Omit", { vm.act { vm.features.omitShot(s.optString("id"), reason) }; omitting = null }, enabled = reason.isNotBlank()) },
            dismissButton = { StudioButton("Cancel", { omitting = null }, variant = BtnVariant.Ghost) })
    }
    pickupScene?.let { sc ->
        var title by remember { mutableStateOf("") }; var why by remember { mutableStateOf("") }
        StudioAlertDialog(onDismissRequest = { pickupScene = null }, title = "Pickup shot", text = {
            Column { StudioField("Shot title", title, { title = it }); StudioField("Why it is needed", why, { why = it }) }
        }, confirmButton = { StudioButton("Add to scene", { vm.act { vm.features.addPickup(sc.id, title, why) }; pickupScene = null }, enabled = title.isNotBlank()) },
            dismissButton = { StudioButton("Cancel", { pickupScene = null }, variant = BtnVariant.Ghost) })
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
        StudioButton(if (vm.importing) "Hashing footage…" else "Import external camera footage ($pendingExternal waiting)", onImportFootage, Modifier.fillMaxWidth(), enabled = !vm.importing && externalRows.isNotEmpty())
        Text("Pick the real files from the SD card / USB reader. Only files whose names were logged are imported, each read in full and SHA-256 verified.",
            style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        StudioButton(if (vm.verifying) "Verifying SHA-256…" else "Verify actual recordings by SHA-256", { vm.verifyRecordedMedia(project.id) }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, enabled = !vm.verifying && mediaRows.isNotEmpty())
        Text("Export production package (metadata only)", style = MaterialTheme.typography.titleLarge)
        StudioButton("Export production metadata JSON", { onExport("json") }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioButton("Markdown", { onExport("md") }, Modifier.weight(1f), variant = BtnVariant.Outline)
            StudioButton("CSV", { onExport("csv") }, Modifier.weight(1f), variant = BtnVariant.Outline)
            StudioButton("ZIP", { onExport("zip") }, Modifier.weight(1f), variant = BtnVariant.Outline)
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
                for (i in 0 until minOf(3, reasons.length())) Text("   " + reasons.getString(i), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
            }
        }
    }
}
