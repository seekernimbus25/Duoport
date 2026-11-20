package app.filmmaking.studio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.data.GeneratedOptions
import app.filmmaking.studio.data.WorkflowKind
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
    return when(item.collection) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectWorkflowScreen(
    project: StudioRecord,
    shots: List<StudioRecord>,
    allRecords: List<StudioRecord>,
    create: (WorkflowKind, String, String, String?) -> Unit,
    update: (String, String, String, String) -> Unit,
    approveTreatment: (String) -> Unit,
    approveScript: (String) -> Unit,
    toggleChecklist: (String) -> Unit,
    signedIn: Boolean,
    generating: Boolean,
    generated: GeneratedOptions?,
    generate: (String, String) -> Unit,
    acceptGenerated: (Int) -> Unit,
    clearGenerated: () -> Unit,
    verifying: Boolean,
    verifyMedia: () -> Unit,
    exportProduction: () -> Unit
) {
    var section by rememberSaveable(project.id) { mutableStateOf("Develop") }
    var creating by remember { mutableStateOf<WorkflowKind?>(null) }
    var editing by remember { mutableStateOf<StudioRecord?>(null) }
    var newTitle by remember { mutableStateOf("") }
    var newDetail by remember { mutableStateOf("") }
    var shotId by remember { mutableStateOf<String?>(null) }
    var shotDropdown by remember { mutableStateOf(false) }
    var aiInstructions by rememberSaveable(project.id) { mutableStateOf("") }

    val records = allRecords.filter {
        JSONObject(it.payload).optString("projectId") == project.id &&
            it.deletedAt == null
    }

    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = sections.indexOf(section).coerceAtLeast(0),
            edgePadding = 8.dp
        ) {
            sections.forEach { tab ->
                Tab(selected = section == tab, onClick = { section = tab }, text = { Text(tab) })
            }
        }
        if (section == "Develop" || section == "Story") {
            val op = if (section == "Develop") "treatments" else "beats"
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = aiInstructions,
                    onValueChange = { aiInstructions = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Optional direction for AI proposals") },
                    maxLines = 3
                )
                OutlinedButton(
                    enabled = signedIn && !generating,
                    onClick = { generate(op, aiInstructions) }
                ) { Text(if (generating) "Generating…" else
                    "Suggest " + if (op == "treatments") "2 treatments" else "2 visual beats") }
                if (!signedIn) Text("Sign in via Settings to generate AI proposals.",
                    style = MaterialTheme.typography.bodySmall)
                if (generated?.op == op) {
                    Text("AI suggestions — only accepted items will be saved",
                        style = MaterialTheme.typography.labelLarge)
                    if (generated.estimatedCostUsd != null) {
                        Text("Estimated model cost: $" + String.format("%.4f", generated.estimatedCostUsd),
                            style = MaterialTheme.typography.labelSmall)
                    }
                    generated.items.forEachIndexed { index, idea ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(idea.optString("title"), style = MaterialTheme.typography.titleMedium)
                                val summary = if (op == "treatments") idea.optString("premise")
                                    else idea.optString("visual")
                                Text(summary, style = MaterialTheme.typography.bodyMedium)
                                TextButton(onClick = { acceptGenerated(index) }) {
                                    Text("Accept as editable draft")
                                }
                            }
                        }
                    }
                    TextButton(onClick = clearGenerated) { Text("Discard remaining AI suggestions") }
                }
            }
        }
        if (section == "Wrap") {
            WrapSummary(shots, records, verifying, verifyMedia, exportProduction)
        } else {
            val kinds = sectionKinds[section].orEmpty()
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kinds.forEach { kind ->
                        OutlinedButton(onClick = {
                            creating = kind
                            editing = null
                            newTitle = ""
                            newDetail = ""
                            shotId = shots.firstOrNull()?.id
                        }) { Text("+ " + kind.label) }
                    }
                }
            }
            val ordered = kinds.flatMap { kind ->
                records.filter { it.collection == kind.collection }.sortedByDescending { it.updatedAt }
            }
            if (ordered.isEmpty()) {
                Text("No " + section.lowercase() + " notes yet. Create one above to begin.",
                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(ordered, key = { it.collection + "/" + it.id }) { item ->
                    val kind = kinds.firstOrNull { it.collection == item.collection }
                    if (kind != null) {
                        WorkflowCard(item, kind, {
                            editing = item
                            creating = kind
                            newTitle = item.title
                            newDetail = recordDetail(item)
                            shotId = JSONObject(item.payload).optString("shotId").takeIf { it.isNotBlank() }
                        }, approveTreatment, approveScript, toggleChecklist)
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
                Column(Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text(if (kind == WorkflowKind.CONTINUITY) "Planned condition" else "Title") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newDetail,
                        onValueChange = { newDetail = it },
                        label = { Text(kind.detailLabel) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4
                    )
                    if (kind == WorkflowKind.CONTINUITY && editing == null) {
                        Box {
                            OutlinedButton(onClick = { shotDropdown = true }) {
                                Text("Shot: " + (shots.find { it.id == shotId }?.title ?: "Select shot"))
                            }
                            DropdownMenu(
                                expanded = shotDropdown,
                                onDismissRequest = { shotDropdown = false }
                            ) {
                                shots.forEach { shot ->
                                    DropdownMenuItem(
                                        text = { Text(JSONObject(shot.payload).optString("code") + " " + shot.title) },
                                        onClick = { shotId = shot.id; shotDropdown = false }
                                    )
                                }
                            }
                        }
                    }
                    if (kind == WorkflowKind.SCRIPT)
                        Text("Approving saves an immutable version. Later edits only change the working draft.",
                            style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newTitle.isNotBlank() &&
                        (kind != WorkflowKind.CONTINUITY || editing != null || shotId != null),
                    onClick = {
                        val item = editing
                        if (item == null) create(kind, newTitle.trim(), newDetail, shotId)
                        else update(item.collection, item.id, newTitle.trim(), newDetail)
                        creating = null
                        editing = null
                    }
                ) { Text("Save offline") }
            },
            dismissButton = { TextButton(onClick = { creating = null; editing = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun WorkflowCard(
    item: StudioRecord, kind: WorkflowKind, edit: () -> Unit,
    approveTreatment: (String) -> Unit, approveScript: (String) -> Unit,
    toggleChecklist: (String) -> Unit
) {
    val json = JSONObject(item.payload)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(kind.label, style = MaterialTheme.typography.labelSmall)
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            if (kind == WorkflowKind.CHECKLIST) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(
                        checked = json.optString("status") == "ready",
                        onCheckedChange = { toggleChecklist(item.id) }
                    )
                    Text(if (json.optString("status") == "ready") "Ready" else "Unresolved")
                }
            }
            val detail = recordDetail(item)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, maxLines = 7)
            if (kind == WorkflowKind.TREATMENT)
                Text("Status: " + json.optString("status"), style = MaterialTheme.typography.labelMedium)
            if (kind == WorkflowKind.SCRIPT) {
                val versions = json.optJSONArray("versions")?.length() ?: 0
                Text(versions.toString() + " saved version(s)", style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!(kind == WorkflowKind.TREATMENT && json.optString("status") == "approved")) {
                    TextButton(onClick = edit) { Text("Edit") }
                }
                if (kind == WorkflowKind.TREATMENT && json.optString("status") != "approved") {
                    TextButton(onClick = { approveTreatment(item.id) }) { Text("Approve") }
                }
                if (kind == WorkflowKind.SCRIPT) {
                    TextButton(onClick = { approveScript(item.id) }) { Text("Approve draft version") }
                }
            }
        }
    }
}

@Composable
private fun WrapSummary(
    shots: List<StudioRecord>, all: List<StudioRecord>,
    verifying: Boolean, verifyMedia: () -> Unit, exportProduction: () -> Unit
) {
    val takeRows = all.filter { it.collection == "takes" }
    val mediaRows = all.filter { it.collection == "media" }
    val externalRows = all.filter { it.collection == "externalClips" }
    val unverified = mediaRows.count { JSONObject(it.payload).optString("verification") != "verified" }
    val completed = shots.count { JSONObject(it.payload).optString("status") == "complete" }
    val continuity = all.filter { it.collection == "continuity" }
    val pendingContinuity = continuity.count { !JSONObject(it.payload).optBoolean("resolved") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Production wrap", style = MaterialTheme.typography.headlineMedium)
        OutlinedButton(onClick = verifyMedia, enabled = !verifying && mediaRows.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()) {
            Text(if (verifying) "Verifying SHA-256…" else "Verify actual recordings by SHA-256")
        }
        Text("Verification reads full media files on this device. Inaccessible files remain unverified.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = exportProduction, modifier = Modifier.fillMaxWidth()) {
            Text("Export production metadata JSON")
        }
        Text(completed.toString() + " of " + shots.size + " planned shots marked complete")
        Text(takeRows.size.toString() + " actual recorded takes")
        Text(mediaRows.size.toString() + " media records")
        Text(externalRows.size.toString() + " external camera filenames logged (not imported footage)")
        if (externalRows.isNotEmpty()) Text(
            "These references will be included in the production metadata export, " +
            "but must be linked to actual camera files before editorial handoff.",
            style = MaterialTheme.typography.bodySmall
        )
        Text(unverified.toString() + " media files not checksum-verified")
        Text(pendingContinuity.toString() + " continuity observations still open")
        HorizontalDivider()
        val hasAll = shots.isNotEmpty() && completed == shots.size
        Text("Planning ready: " + if (shots.isNotEmpty()) "Yes" else "No planned shots")
        Text("Shooting complete: " + if (hasAll && takeRows.isNotEmpty()) "Provisional" else "Not yet")
        Text("Actual media verified: " + if (mediaRows.isNotEmpty() && unverified == 0) "Yes" else "Not yet")
        Text("Editorial handoff: requires separately validated production export and media relinking",
            style = MaterialTheme.typography.bodySmall)
    }
}
