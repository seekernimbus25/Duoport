package app.filmmaking.studio

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.filmmaking.studio.data.ExternalCameraNaming
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.sync.Resolution
import app.filmmaking.studio.sync.SyncOp
import org.json.JSONArray
import org.json.JSONObject

private val big = Modifier.fillMaxWidth().heightIn(min = 64.dp)

/**
 * #17 Shoot Mode: full screen, ≥64 dp targets, fully offline.
 * Log a take, rate / prefer it, mark the shot complete, move to the next shot.
 */
@Composable
fun ShootModeScreen(
    vm: StudioViewModel, project: StudioRecord, shots: List<StudioRecord>, all: List<StudioRecord>,
    onRecord: (StudioRecord) -> Unit, onExit: () -> Unit
) {
    val days = all.filter { it.collection == "shootDays" && it.deletedAt == null && data(it).optString("projectId") == project.id }
        .sortedBy { data(it).optString("date") }
    val dayOrder = days.flatMap { d -> data(d).optJSONArray("order")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList() }
    val ordered = (dayOrder.mapNotNull { id -> shots.find { it.id == id } } +
        shots.filter { it.id !in dayOrder }.sortedBy { data(it).optDouble("shootOrder") }).distinctBy { it.id }
    var index by rememberSaveable(project.id) { mutableIntStateOf(0) }
    val shot = ordered.getOrNull(index.coerceIn(0, (ordered.size - 1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Shoot Mode", style = MaterialTheme.typography.headlineSmall)
        if (shot == null) { Text("No shots planned."); Button(onClick = onExit, modifier = big) { Text("Exit Shoot Mode") }; return@Column }
        val p = data(shot)
        Text("Shot " + (index + 1) + " of " + ordered.size, style = MaterialTheme.typography.labelLarge)
        Text(p.optString("code") + " · " + shot.title, style = MaterialTheme.typography.headlineMedium)
        Text(p.optString("description").ifBlank { "No action notes" })
        p.optJSONObject("camera")?.let { Text(it.optString("shotSize") + " · " + it.optString("lens") + " · " + it.optString("frameRate") + " fps (plan)") }
        Text("Status: " + p.optString("status"))
        Button(onClick = { onRecord(shot) }, modifier = big) { Text("Record in Duoport") }
        val clips = all.filter { it.collection == ExternalCameraNaming.COLLECTION && it.deletedAt == null && data(it).optString("projectId") == project.id }
        val next = ExternalCameraNaming.nextFrom(clips)?.nextFilename
        if (next != null) OutlinedButton(onClick = { vm.logExternalCameraFilename(shot.id, next) }, modifier = big) { Text("Log external file $next") }
        OutlinedButton(onClick = { vm.act { vm.features.logSoundTake(shot.id, "roomTone", "Logged in Shoot Mode"); vm.report("Room tone take logged (separate recorder)") } }, modifier = big) {
            Text("Log room tone take")
        }
        val takes = all.filter { it.collection == "takes" && it.deletedAt == null && data(it).optString("shotId") == shot.id }.sortedBy { data(it).optInt("number") }
        val last = takes.lastOrNull()
        if (last != null) {
            val t = data(last)
            Text("Last: Take " + t.optInt("number") + " · " + t.optString("kind").ifBlank { "picture" } + " · " + t.optString("quality") +
                if (t.optBoolean("preferred")) " · preferred" else "", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.rateTake(last.id, "usable") }, Modifier.weight(1f).heightIn(min = 64.dp)) { Text("Usable") }
                OutlinedButton(onClick = { vm.rateTake(last.id, "unusable") }, Modifier.weight(1f).heightIn(min = 64.dp)) { Text("Unusable") }
            }
            if (!t.optBoolean("preferred")) OutlinedButton(onClick = { vm.preferTake(last.id) }, modifier = big) { Text("Prefer this take") }
        } else Text("No takes yet for this shot")
        Button(onClick = { vm.changeShot(shot.id, "complete") }, modifier = big, enabled = p.optString("status") != "complete") { Text("Mark shot complete") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { index = (index - 1).coerceAtLeast(0) }, Modifier.weight(1f).heightIn(min = 64.dp), enabled = index > 0) { Text("Previous") }
            Button(onClick = { index = (index + 1).coerceAtMost(ordered.size - 1) }, Modifier.weight(1f).heightIn(min = 64.dp), enabled = index < ordered.size - 1) { Text("Next shot") }
        }
        TextButton(onClick = onExit, modifier = big) { Text("Exit Shoot Mode") }
    }
}

/** #19 extras on the shot screen: sound / room-tone takes, star ratings, director notes. */
@Composable
fun TakeExtras(vm: StudioViewModel, shot: StudioRecord, takes: List<StudioRecord>) {
    var notes by rememberSaveable(shot.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sound takes (separate recorder)", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("Sound take notes (file name on recorder)") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { vm.act { vm.features.logSoundTake(shot.id, "audio", notes) }; notes = "" }) { Text("Log audio take") }
            OutlinedButton(onClick = { vm.act { vm.features.logSoundTake(shot.id, "roomTone", notes) }; notes = "" }) { Text("Log room tone") }
        }
        takes.forEach { take ->
            val t = data(take)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("T" + t.optInt("number") + " " + t.optString("kind").ifBlank { "picture" } + " ", style = MaterialTheme.typography.bodySmall)
                (1..5).forEach { n ->
                    TextButton(onClick = { vm.act { vm.features.rateTakeStars(take.id, n) } }, contentPadding = PaddingValues(4.dp)) {
                        Text(if (t.optInt("rating") >= n) "★" else "☆")
                    }
                }
            }
        }
    }
}

/** #25 Cloud copy & sync: status, Sync now and per-record conflict decisions. */
@Composable
fun CloudSyncSection(vm: StudioViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cloud sync", style = MaterialTheme.typography.titleLarge)
        if (vm.session == null) {
            Text("Sign in to copy this device's films to your Studio cloud and sync with your other devices.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text("Last result: " + vm.syncSummary, style = MaterialTheme.typography.bodySmall)
        Text("Background sync runs every few hours on any network. No last-write-wins: when both devices changed a record you decide.",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = !vm.syncing, onClick = { vm.syncNow() }) { Text(if (vm.syncing) "Syncing…" else "Sync now") }
        if (vm.conflicts.isNotEmpty()) {
            Text(vm.conflicts.size.toString() + " conflict(s) — choose per item", style = MaterialTheme.typography.titleMedium)
            val choices = remember(vm.conflicts) { mutableStateMapOf<String, Resolution>() }
            vm.conflicts.forEach { c -> ConflictCard(c, choices[c.key]) { choice -> if (choice == null) choices.remove(c.key) else choices[c.key] = choice } }
            Button(enabled = choices.isNotEmpty() && !vm.syncing, onClick = { vm.syncNow(choices.toMap()) }) {
                Text("Apply " + choices.size + " decision(s)")
            }
            Text("Items left on “Decide later” stay untouched on both sides.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ConflictCard(c: SyncOp.Conflict, choice: Resolution?, choose: (Resolution?) -> Unit) {
    val title = c.local?.payload?.let { app.filmmaking.studio.sync.CloudMapping.titleOf(it, c.id) }
        ?: app.filmmaking.studio.sync.CloudMapping.titleOf(c.remote.payload, c.id)
    val why = when (c.reason) {
        "both-edited" -> "Changed on this device and in the cloud"
        "edited-here-deleted-in-cloud" -> "Changed here but deleted on another device"
        "deleted-here-edited-in-cloud" -> "Deleted here but changed on another device"
        else -> "Different copies on first sync"
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(c.table.replace('_', ' ') + " · " + title, style = MaterialTheme.typography.titleSmall)
            Text(why, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = choice == Resolution.MINE, onClick = { choose(Resolution.MINE) }, label = { Text("Keep this device") })
                FilterChip(selected = choice == Resolution.CLOUD, onClick = { choose(Resolution.CLOUD) }, label = { Text("Keep cloud") })
                FilterChip(selected = choice == null, onClick = { choose(null) }, label = { Text("Decide later") })
            }
        }
    }
}

/** #27: shows whether the camera profile is device-reported (verified) or not. */
@Composable
fun CameraProfileSection(vm: StudioViewModel, profile: StudioRecord?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Camera profile", style = MaterialTheme.typography.titleLarge)
        if (profile == null) Text("Not read yet. Shot plans are recommendations until this phone reports its capabilities.", style = MaterialTheme.typography.bodySmall)
        else {
            val p = data(profile)
            Text(p.optString("device") + " · " + (if (p.optBoolean("verified")) "verified (device-reported)" else "user-entered, not verified"))
            Text("Frame rates: " + (p.optJSONArray("fps")?.join(", ") ?: "?") + " · video short sides: " + (p.optJSONArray("videoShortSides")?.join(", ") ?: "?"),
                style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = { vm.probeCamera() }) { Text("Read this phone's camera capabilities") }
    }
}

/** #1: idea links / images / voice notes; the original text is never edited. */
@Composable
fun IdeaExtras(vm: StudioViewModel, idea: StudioRecord, onPickImage: () -> Unit, recording: Boolean, onToggleVoice: () -> Unit) {
    var link by rememberSaveable(idea.id) { mutableStateOf("") }
    var expansion by rememberSaveable(idea.id + "x") { mutableStateOf("") }
    val d = data(idea)
    val attachments = (d.optJSONArray("attachments") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } +
        (d.optJSONArray("sources") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    val expansions = d.optJSONArray("expansions") ?: JSONArray()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        attachments.forEach { Text("• " + it.optString("kind") + ": " + it.optString("label").ifBlank { it.optString("uri").takeLast(60) }, style = MaterialTheme.typography.bodySmall) }
        for (i in 0 until expansions.length()) Text("+ " + expansions.getJSONObject(i).optString("text"), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(onClick = onPickImage) { Text("Add image") }
            TextButton(onClick = onToggleVoice) { Text(if (recording) "Stop voice note" else "Record voice note") }
        }
        OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), label = { Text("Link (https://…)") }, singleLine = true)
        TextButton(enabled = link.isNotBlank(), onClick = { vm.act { vm.features.attachToIdea(idea.id, "link", link.trim()) }; link = "" }) { Text("Attach link") }
        OutlinedTextField(expansion, { expansion = it }, Modifier.fillMaxWidth(), label = { Text("Add a thought (original stays unchanged)") })
        TextButton(enabled = expansion.isNotBlank(), onClick = { vm.act { vm.features.expandIdea(idea.id, expansion.trim()) }; expansion = "" }) { Text("Add to idea") }
    }
}

/** #4: persistent conversations, mode / role, archive retrieval with citations. */
@Composable
fun AiDirectorScreen(vm: StudioViewModel, projects: List<StudioRecord>, all: List<StudioRecord>) {
    var input by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("anthropic") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("Explore") }
    var role by rememberSaveable { mutableStateOf("Creative Partner") }
    var archive by rememberSaveable { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val project = projects.find { it.id == projectId }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI Director", style = MaterialTheme.typography.titleLarge)
        if (vm.session == null) {
            Text("Sign in under Settings using your existing Studio Supabase account.")
            Text("Model API keys are securely stored on AWS, not in this APK.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val conversations = all.filter { it.collection == "aiConversations" && it.deletedAt == null }.sortedByDescending { it.updatedAt }
        val messages = all.filter { it.collection == "aiMessages" && it.deletedAt == null && data(it).optString("conversationId") == vm.conversationId }
            .sortedBy { data(it).optString("at") }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = provider == "anthropic", onClick = { provider = "anthropic" }, label = { Text("Claude Haiku") })
            FilterChip(selected = provider == "openai", onClick = { provider = "openai" }, label = { Text("GPT-5 mini") })
            FilterChip(selected = archive, onClick = { archive = !archive }, label = { Text("Use my archive") })
            listOf("Explore", "Challenge", "Develop", "Teach", "Execute").forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Creative Partner", "Script Doctor", "Cinematographer", "Director", "Producer", "Editorial Advisor").forEach { r ->
                FilterChip(selected = role == r, onClick = { role = r }, label = { Text(r) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box { OutlinedButton(onClick = { showProjects = true }) { Text("Film: " + (project?.title ?: "General")) }
                DropdownMenu(showProjects, { showProjects = false }) {
                    DropdownMenuItem({ Text("General conversation") }, { projectId = null; showProjects = false; vm.clearChat() })
                    projects.forEach { p -> DropdownMenuItem({ Text(p.title) }, { projectId = p.id; showProjects = false; vm.clearChat() }) }
                } }
            Box { OutlinedButton(onClick = { showConversations = true }) { Text("History (" + conversations.size + ")") }
                DropdownMenu(showConversations, { showConversations = false }) {
                    DropdownMenuItem({ Text("New conversation") }, { vm.clearChat(); showConversations = false })
                    conversations.forEach { c -> DropdownMenuItem({ Text(c.title.take(40)) }, { vm.conversationId = c.id; showConversations = false }) }
                } }
        }
        Text("Conversations are saved on this device and synced to your cloud.", style = MaterialTheme.typography.bodySmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { m ->
                val d = data(m)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (d.optString("from") == "user") "You" else "AI Director", style = MaterialTheme.typography.labelLarge)
                        Text(d.optString("text"))
                        val cites = d.optJSONArray("citations") ?: JSONArray()
                        for (i in 0 until cites.length()) {
                            val c = cites.getJSONObject(i)
                            Text("[" + c.optString("label") + "] " + c.optString("title") + (if (c.has("page")) " p." + c.optInt("page") else "") +
                                ": “" + c.optString("excerpt").take(160) + "”", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (vm.archiveResults.isNotEmpty()) items(vm.archiveResults) { r ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Text(r.optString("title") + (if (!r.isNull("page")) " p." + r.optInt("page") else "") + ": " + r.optString("content").take(240),
                        Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        vm.lastCost?.let { Text("Last estimated provider cost: $" + String.format("%.4f", it), style = MaterialTheme.typography.labelSmall) }
        OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), label = { Text("Ask about story, shots, tone or direction") }, maxLines = 4)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(enabled = input.isNotBlank() && !vm.sending, onClick = { vm.ask(input, provider, project, archive, mode, role); input = "" }, modifier = Modifier.weight(1f)) {
                Text(if (vm.sending) "Thinking…" else "Send to AI Director")
            }
            OutlinedButton(enabled = input.isNotBlank(), onClick = { vm.searchArchive(input) }) { Text("Search archive") }
        }
    }
}
