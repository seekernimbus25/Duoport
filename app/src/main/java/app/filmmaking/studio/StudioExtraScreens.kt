package app.filmmaking.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.filmmaking.studio.data.ExternalCameraNaming
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.sync.Resolution
import app.filmmaking.studio.sync.SyncOp
import app.filmmaking.studio.ui.*
import org.json.JSONArray

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
    Column(Modifier.fillMaxSize().background(StudioColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(project.title, color = StudioColors.Primary)
                Text("Shoot Mode", style = MaterialTheme.typography.headlineSmall)
            }
            if (shot != null) Eyebrow("Shot " + (index + 1) + " of " + ordered.size)
        }
        if (ordered.isNotEmpty()) GoldProgress((index + 1) * 100f / ordered.size, Modifier.padding(horizontal = 20.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (shot == null) {
                EmptyState("No shots planned", "Add shots in the Shots tab, then come back to shoot.")
                StudioButton("Exit Shoot Mode", onExit, big, variant = BtnVariant.Outline)
                return@Column
            }
            val p = data(shot)
            StudioCard(padding = 20.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.optString("code"), Modifier.padding(end = 12.dp), style = MonoStyle.copy(fontSize = 16.sp), color = StudioColors.Primary)
                    Text(shot.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    StudioChip(p.optString("status"), tone = if (p.optString("status") == "complete") ChipTone.Success else ChipTone.Default)
                }
                Spacer(Modifier.height(10.dp))
                Text(p.optString("description").ifBlank { "No action notes" }, style = MaterialTheme.typography.bodyLarge,
                    color = if (p.optString("description").isBlank()) StudioColors.MutedForeground else StudioColors.Foreground)
                p.optJSONObject("camera")?.let {
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Meta("Size", it.optString("shotSize"), Modifier.weight(1f))
                        Meta("Lens", it.optString("lens"), Modifier.weight(1f))
                        Meta("FPS (plan)", it.optString("frameRate"), Modifier.weight(1f))
                    }
                }
            }
            StudioButton("Record in Duoport", { onRecord(shot) }, big, icon = Lucide.Video)
            val clips = all.filter { it.collection == ExternalCameraNaming.COLLECTION && it.deletedAt == null && data(it).optString("projectId") == project.id }
            val next = ExternalCameraNaming.nextFrom(clips)?.nextFilename
            if (next != null) StudioButton("Log external file $next", { vm.logExternalCameraFilename(shot.id, next) }, big, variant = BtnVariant.Outline, icon = Lucide.Camera)
            StudioButton("Log room tone take", {
                vm.act { vm.features.logSoundTake(shot.id, "roomTone", "Logged in Shoot Mode"); vm.report("Room tone take logged (separate recorder)") }
            }, big, variant = BtnVariant.Outline, icon = Lucide.Mic)

            val takes = all.filter { it.collection == "takes" && it.deletedAt == null && data(it).optString("shotId") == shot.id }.sortedBy { data(it).optInt("number") }
            val last = takes.lastOrNull()
            Spacer(Modifier.height(4.dp))
            if (last != null) {
                val t = data(last)
                StudioCard {
                    Eyebrow("Last take")
                    Text("Take " + t.optInt("number") + " · " + t.optString("kind").ifBlank { "picture" } + " · " + t.optString("quality") +
                        if (t.optBoolean("preferred")) " · preferred" else "", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioButton("Usable", { vm.rateTake(last.id, "usable") }, Modifier.weight(1f).heightIn(min = 64.dp),
                            variant = if (t.optString("quality") == "usable") BtnVariant.Primary else BtnVariant.Outline, icon = Lucide.Check)
                        StudioButton("Unusable", { vm.rateTake(last.id, "unusable") }, Modifier.weight(1f).heightIn(min = 64.dp),
                            variant = BtnVariant.Danger, icon = Lucide.X)
                    }
                    if (!t.optBoolean("preferred")) {
                        Spacer(Modifier.height(8.dp))
                        StudioButton("Prefer this take", { vm.preferTake(last.id) }, big, variant = BtnVariant.Outline, icon = Lucide.Star)
                    }
                }
            } else MutedText("No takes yet for this shot")
            StudioButton("Mark shot complete", { vm.changeShot(shot.id, "complete") }, big, enabled = p.optString("status") != "complete", icon = Lucide.CircleCheck)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioButton("Previous", { index = (index - 1).coerceAtLeast(0) }, Modifier.weight(1f).heightIn(min = 64.dp), variant = BtnVariant.Outline, enabled = index > 0)
                StudioButton("Next shot", { index = (index + 1).coerceAtMost(ordered.size - 1) }, Modifier.weight(1f).heightIn(min = 64.dp), enabled = index < ordered.size - 1)
            }
            StudioButton("Exit Shoot Mode", onExit, big, variant = BtnVariant.Ghost)
        }
    }
}

/** #19 extras on the shot screen: sound / room-tone takes and star ratings. */
@Composable
fun TakeExtras(vm: StudioViewModel, shot: StudioRecord, takes: List<StudioRecord>) {
    var notes by rememberSaveable(shot.id) { mutableStateOf("") }
    StudioSection("Sound takes", "Separate recorder: log the file name it shows.") {
        StudioField("Sound take notes (file name on recorder)", notes, { notes = it })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioButton("Log audio take", { vm.act { vm.features.logSoundTake(shot.id, "audio", notes) }; notes = "" }, Modifier.weight(1f), variant = BtnVariant.Outline, icon = Lucide.Mic)
            StudioButton("Log room tone", { vm.act { vm.features.logSoundTake(shot.id, "roomTone", notes) }; notes = "" }, Modifier.weight(1f), variant = BtnVariant.Outline, icon = Lucide.Volume2)
        }
        if (takes.isNotEmpty()) {
            Eyebrow("Rate takes", Modifier.padding(top = 4.dp))
            StudioCard(padding = 0.dp) {
                takes.forEachIndexed { i, take ->
                    val t = data(take)
                    if (i > 0) StudioDivider()
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("T" + t.optInt("number") + " · " + t.optString("kind").ifBlank { "picture" }, Modifier.weight(1f), style = MonoStyle.copy(fontSize = 13.sp))
                        (1..5).forEach { n ->
                            Box(Modifier.size(40.dp, 48.dp).clickable(role = Role.Button) { vm.act { vm.features.rateTakeStars(take.id, n) } },
                                contentAlignment = Alignment.Center) {
                                Text(if (t.optInt("rating") >= n) "★" else "☆", fontSize = 20.sp,
                                    color = if (t.optInt("rating") >= n) StudioColors.Primary else StudioColors.MutedForeground)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** #25 Cloud copy & sync: status, Sync now and per-record conflict decisions. */
@Composable
fun CloudSyncSection(vm: StudioViewModel) {
    StudioSection("Cloud sync") {
        if (vm.session == null) {
            StudioCard { MutedText("Sign in to copy this device's films to your Studio cloud and sync with your other devices.") }
            return@StudioSection
        }
        vm.syncBlocked?.let { why ->
            StudioCard {
                Text(why, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                StudioButton("Check again / link this phone's work", { vm.reviewLink() }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, enabled = !vm.restoring)
            }
            return@StudioSection
        }
        StudioCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Cloud, null, Modifier.size(18.dp), tint = StudioColors.Primary)
                Spacer(Modifier.width(8.dp))
                Text("Last result: " + vm.syncSummary, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(6.dp))
            MutedText("Background sync runs every few hours on any network. No last-write-wins: when both devices changed a record you decide.", small = true)
            Spacer(Modifier.height(12.dp))
            StudioButton(if (vm.syncing) "Syncing…" else "Sync now", { vm.syncNow() }, Modifier.fillMaxWidth(), enabled = !vm.syncing, icon = Lucide.RefreshCw)
        }
        if (vm.conflicts.isNotEmpty()) {
            Text(vm.conflicts.size.toString() + " conflict(s) — choose per item", style = MaterialTheme.typography.titleMedium)
            val choices = remember(vm.conflicts) { mutableStateMapOf<String, Resolution>() }
            vm.conflicts.forEach { c -> ConflictCard(c, choices[c.key]) { choice -> if (choice == null) choices.remove(c.key) else choices[c.key] = choice } }
            StudioButton("Apply " + choices.size + " decision(s)", { vm.syncNow(choices.toMap()) }, Modifier.fillMaxWidth(), enabled = choices.isNotEmpty() && !vm.syncing)
            MutedText("Items left on “Decide later” stay untouched on both sides.", small = true)
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
    StudioCard(padding = 14.dp) {
        Eyebrow(c.table.replace('_', ' '))
        Text(title, style = MaterialTheme.typography.titleSmall)
        MutedText(why, small = true)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StudioChip("Keep this device", active = choice == Resolution.MINE, onClick = { choose(Resolution.MINE) })
            StudioChip("Keep cloud", active = choice == Resolution.CLOUD, onClick = { choose(Resolution.CLOUD) })
            StudioChip("Decide later", active = choice == null, onClick = { choose(null) })
        }
    }
}

/** #27: shows whether the camera profile is device-reported (verified) or not. */
@Composable
fun CameraProfileSection(vm: StudioViewModel, profile: StudioRecord?) {
    StudioSection("Camera profile") {
        StudioCard {
            if (profile == null) MutedText("Not read yet. Shot plans are recommendations until this phone reports its capabilities.")
            else {
                val p = data(profile)
                val verified = p.optBoolean("verified")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.optString("device"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    StudioChip(if (verified) "verified (device-reported)" else "user-entered, not verified",
                        tone = if (verified) ChipTone.Success else ChipTone.Gold)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Meta("Frame rates", p.optJSONArray("fps")?.join(", ") ?: "?", Modifier.weight(1f))
                    Meta("Video short sides", p.optJSONArray("videoShortSides")?.join(", ") ?: "?", Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(12.dp))
            StudioButton("Read this phone's camera capabilities", { vm.probeCamera() }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, icon = Lucide.Smartphone)
        }
    }
}

private val MODES = listOf("Explore", "Challenge", "Develop", "Teach", "Execute")
private val ROLES = listOf("Creative Partner", "Script Doctor", "Cinematographer", "Director", "Producer", "Editorial Advisor")

/** #4: persistent conversations, mode / role, archive retrieval with citations (web `AssistantPanel`). */
@Composable
fun AiDirectorScreen(vm: StudioViewModel, projects: List<StudioRecord>, all: List<StudioRecord>, onAddDocument: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("anthropic") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var mode by rememberSaveable { mutableStateOf("Explore") }
    var role by rememberSaveable { mutableStateOf("Creative Partner") }
    var archive by rememberSaveable { mutableStateOf(false) }
    var showProjects by remember { mutableStateOf(false) }
    var showConversations by remember { mutableStateOf(false) }
    val project = projects.find { it.id == projectId }
    if (vm.session == null) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EmptyState("Sign in for live AI", "Sign in under Library → Account & data using your existing Studio Supabase account.")
            MutedText("Model API keys are securely stored on AWS, not in this APK.", small = true)
        }
        return
    }
    val conversations = all.filter { it.collection == "aiConversations" && it.deletedAt == null }.sortedByDescending { it.updatedAt }
    val messages = all.filter { it.collection == "aiMessages" && it.deletedAt == null && data(it).optString("conversationId") == vm.conversationId }
        .sortedBy { data(it).optString("at") }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1) }
    Column(Modifier.fillMaxSize()) {
        // Context controls (web: film select, mode chips, archive chip).
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) {
                    StudioButton("Film: " + (project?.title ?: "General"), { showProjects = true }, Modifier.fillMaxWidth(), variant = BtnVariant.Outline, icon = Lucide.Clapperboard)
                    DropdownMenu(showProjects, { showProjects = false }) {
                        DropdownMenuItem({ Text("General conversation") }, { projectId = null; showProjects = false; vm.clearChat() })
                        projects.forEach { p -> DropdownMenuItem({ Text(p.title) }, { projectId = p.id; showProjects = false; vm.clearChat() }) }
                    }
                }
                Box {
                    StudioButton("History (" + conversations.size + ")", { showConversations = true }, variant = BtnVariant.Outline, icon = Lucide.History)
                    DropdownMenu(showConversations, { showConversations = false }) {
                        DropdownMenuItem({ Text("New conversation") }, { vm.clearChat(); showConversations = false })
                        conversations.forEach { c -> DropdownMenuItem({ Text(c.title.take(40)) }, { vm.conversationId = c.id; showConversations = false }) }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MODES.forEach { m -> StudioChip(m, active = mode == m, onClick = { mode = m }) }
                StudioChip("Archive " + if (archive) "on" else "off", active = archive, onClick = { archive = !archive }, leading = Lucide.BookOpen)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ROLES.forEach { r -> StudioChip(r, active = role == r, onClick = { role = r }) }
                StudioChip("Claude Haiku", active = provider == "anthropic", tone = ChipTone.Gold, onClick = { provider = "anthropic" })
                StudioChip("GPT-5 mini", active = provider == "openai", tone = ChipTone.Gold, onClick = { provider = "openai" })
            }
        }
        StudioDivider()
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (messages.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Lucide.Sparkles, null, Modifier.size(28.dp), tint = StudioColors.Primary)
                    Spacer(Modifier.height(8.dp))
                    Text("Think out loud.", style = MaterialTheme.typography.titleLarge)
                    MutedText("Suggestions never change your work until you accept them. Conversations are saved on this device and synced to your cloud.")
                }
            }
            items(messages, key = { it.id }) { m ->
                val d = data(m)
                val mine = d.optString("from") == "user"
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.fillMaxWidth(0.88f).wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
                        .clip(RoundedCornerShape(18.dp)).background(if (mine) StudioColors.Primary else StudioColors.SurfaceRaised)
                        .padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!mine) Eyebrow("AI Director", color = StudioColors.Primary)
                        Text(d.optString("text"), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                            color = if (mine) StudioColors.PrimaryForeground else StudioColors.Foreground)
                        val cites = d.optJSONArray("citations") ?: JSONArray()
                        if (cites.length() > 0) {
                            StudioDivider()
                            Eyebrow("Sources consulted (" + cites.length() + ")")
                            for (i in 0 until cites.length()) {
                                val c = cites.getJSONObject(i)
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(StudioColors.Background.copy(alpha = 0.5f)).padding(8.dp)) {
                                    Text("[" + c.optString("label") + "] " + c.optString("title") + (if (c.has("page")) " · p." + c.optInt("page") else ""),
                                        style = MaterialTheme.typography.labelMedium, color = StudioColors.Primary)
                                    MutedText(c.optString("excerpt").take(160), small = true)
                                }
                            }
                        }
                    }
                }
            }
            if (vm.sending) item { MutedText("Thinking…") }
            if (vm.archiveResults.isNotEmpty()) {
                item { Eyebrow("Archive passages") }
                items(vm.archiveResults) { r ->
                    StudioCard(padding = 12.dp) {
                        Text(r.optString("title") + (if (!r.isNull("page")) " · p." + r.optInt("page") else ""), style = MaterialTheme.typography.labelMedium, color = StudioColors.Primary)
                        MutedText(r.optString("content").take(240), small = true)
                    }
                }
            }
        }
        StudioDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioSearchlessField(input, { input = it }, "Ask the " + role.lowercase() + "…", Modifier.weight(1f))
                Box(Modifier.size(48.dp).clip(CircleShape).background(if (input.isNotBlank() && !vm.sending) StudioColors.Primary else StudioColors.Primary.copy(alpha = 0.4f))
                    .clickable(enabled = input.isNotBlank() && !vm.sending, role = Role.Button) { vm.ask(input, provider, project, archive, mode, role); input = "" },
                    contentAlignment = Alignment.Center) {
                    Icon(Lucide.Send, "Send to AI Director", Modifier.size(20.dp), tint = StudioColors.PrimaryForeground)
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StudioChip("Search archive", leading = Lucide.Search, onClick = if (input.isNotBlank()) ({ vm.searchArchive(input) }) else null)
                StudioChip(if (vm.aiBusy) "Indexing…" else "Add PDF / DOCX / text to my private archive", leading = Lucide.Upload,
                    onClick = if (!vm.aiBusy) onAddDocument else null)
            }
            vm.lastCost?.let { Text("Last estimated provider cost: $" + String.format("%.4f", it), style = EyebrowStyle.copy(letterSpacing = 0.sp)) }
        }
    }
}

/** Single-line-ish chat input in the field style, no label. */
@Composable
private fun StudioSearchlessField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier) =
    OutlinedTextField(value, onChange, modifier, maxLines = 4,
        placeholder = { Text(placeholder, color = StudioColors.MutedForeground.copy(alpha = 0.7f)) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = StudioColors.Surface, unfocusedContainerColor = StudioColors.Surface,
            focusedBorderColor = StudioColors.Ring, unfocusedBorderColor = StudioColors.Input, cursorColor = StudioColors.Primary))

/**
 * #24 Creative DNA. Explicit preferences are typed by the creator; discovery reads chosen archive
 * documents and only proposes patterns backed by verbatim quotes. Nothing proposed is used by the AI
 * until it is approved; experiments stay temporary unless saved to one film.
 */
@Composable
fun CreativeDnaScreen(vm: StudioViewModel, projects: List<StudioRecord>, all: List<StudioRecord>) {
    val memories = all.filter { it.collection == "memories" && it.deletedAt == null }
    val explicit = memories.filter { data(it).optString("status") == "approved" && data(it).optString("scope") == "identity" }
    val pending = memories.filter { data(it).optString("status") == "proposed" }
    val projectChoices = memories.filter { data(it).optString("status") == "approved" && data(it).optString("scope") == "project" }
    var preference by rememberSaveable { mutableStateOf("") }
    var focus by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("anthropic") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            MutedText("Long-term identity. The assistant reads this; it only changes when you edit it or approve a suggestion.")
            Spacer(Modifier.height(6.dp))
            MutedText(explicit.size.toString() + " explicit preference(s) · " + pending.size + " inferred awaiting approval · " +
                projectChoices.size + " project choice(s). Only approved items are sent to the AI.", small = true)
        }
        item { SectionTitle("Explicit preferences") }
        items(explicit, key = { it.id }) { m -> MemoryCard(vm, m, editable = true) }
        item {
            StudioCard {
                StudioField("Your preference", preference, { preference = it }, placeholder = "e.g. I prefer long, observational takes", minLines = 2)
                Spacer(Modifier.height(12.dp))
                StudioButton("Add preference", { vm.act { vm.features.addPreference(preference) }; preference = "" }, Modifier.fillMaxWidth(),
                    enabled = preference.trim().length >= 3, icon = Lucide.Plus)
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            SectionTitle("Discover patterns in your work")
            MutedText("The AI reads selected archive documents and suggests possible patterns, each backed by quotes. " +
                "Nothing becomes part of your Creative DNA until you approve it.", small = true)
            Spacer(Modifier.height(12.dp))
            if (vm.session == null) StudioCard { MutedText("Sign in under Library → Account & data to analyse your archive.") }
            else StudioButton(if (vm.archiveDocs.isEmpty()) "Load my indexed documents" else "Refresh documents", { vm.loadArchiveDocs() },
                Modifier.fillMaxWidth(), variant = BtnVariant.Outline, enabled = !vm.aiBusy, icon = Lucide.Database)
        }
        items(vm.archiveDocs, key = { "doc:" + it.optString("id") }) { d ->
            val id = d.optString("id")
            val on = id in picked
            val canPick = on || picked.size < 8
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
                .background(if (on) StudioColors.GoldSoft else StudioColors.Surface)
                .border(1.dp, if (on) StudioColors.Primary else StudioColors.Border, RoundedCornerShape(12.dp))
                .clickable(enabled = canPick, role = Role.Checkbox) { picked = if (on) picked - id else picked + id }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (on) Lucide.CircleCheck else Lucide.Circle, null, Modifier.size(18.dp), tint = if (on) StudioColors.Primary else StudioColors.MutedForeground)
                Spacer(Modifier.width(10.dp))
                Text(d.optString("title"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (vm.archiveDocs.isNotEmpty()) item {
            StudioField("Optional focus", focus, { focus = it }, placeholder = "e.g. how I write voiceovers")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StudioChip("Claude Haiku 4.5", active = provider == "anthropic", onClick = { provider = "anthropic" })
                StudioChip("GPT-5 mini", active = provider == "openai", onClick = { provider = "openai" })
            }
            Spacer(Modifier.height(10.dp))
            StudioButton(if (vm.aiBusy) "Reading…" else "Analyse " + picked.size + " document(s)", { vm.discoverDna(picked.toList(), provider, focus) },
                Modifier.fillMaxWidth(), enabled = picked.isNotEmpty() && !vm.aiBusy, icon = Lucide.WandSparkles)
            Spacer(Modifier.height(6.dp))
            MutedText("Up to 8 documents. Cost is checked against your per-request and daily AI limits on the server.", small = true)
        }
        if (pending.isNotEmpty()) item { Spacer(Modifier.height(12.dp)); SectionTitle("Inferred — awaiting approval") }
        items(pending, key = { it.id }) { m -> MemoryCard(vm, m, editable = true) }
        if (vm.dnaExperiments.isNotEmpty()) item { Spacer(Modifier.height(12.dp)); SectionTitle("Temporary experiments — beyond your habits (not saved)") }
        items(vm.dnaExperiments) { x ->
            var choose by remember { mutableStateOf(false) }
            StudioCard(padding = 14.dp) {
                Text(x.optString("text"), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box {
                        StudioButton("Try in a film…", { choose = true }, variant = BtnVariant.Outline, enabled = projects.isNotEmpty())
                        DropdownMenu(choose, { choose = false }) {
                            projects.forEach { p -> DropdownMenuItem({ Text(p.title) }, {
                                choose = false
                                vm.act { vm.features.saveExperiment(p.id, x.optString("text"), x.optString("category")); vm.dismissExperiment(x)
                                    vm.report("Saved as an experiment for " + p.title) }
                            }) }
                        }
                    }
                    StudioButton("Dismiss", { vm.dismissExperiment(x) }, variant = BtnVariant.Ghost)
                }
            }
        }
        if (projectChoices.isNotEmpty()) item { Spacer(Modifier.height(12.dp)); SectionTitle("Project choices & experiments") }
        items(projectChoices, key = { it.id }) { m ->
            val film = projects.find { it.id == data(m).optString("projectId") }?.title ?: "film"
            MemoryCard(vm, m, editable = false, note = "Only for " + film)
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun MemoryCard(vm: StudioViewModel, m: StudioRecord, editable: Boolean, note: String? = null) {
    val d = data(m)
    val proposed = d.optString("status") == "proposed"
    var text by remember(m.id, d.optString("text")) { mutableStateOf(d.optString("text")) }
    StudioCard(padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioChip(if (proposed) "Inferred" else if (d.optString("source") == "user") "Explicit" else "Approved",
                tone = if (proposed) ChipTone.Gold else ChipTone.Success)
            Eyebrow(listOfNotNull(d.optString("category").ifBlank { null }, note).joinToString(" · "))
        }
        Spacer(Modifier.height(8.dp))
        if (editable) OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), maxLines = 4,
            textStyle = MaterialTheme.typography.bodyMedium, shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = StudioColors.Surface, unfocusedContainerColor = Color0,
                focusedBorderColor = StudioColors.Ring, unfocusedBorderColor = StudioColors.Border, cursorColor = StudioColors.Primary))
        else Text(d.optString("text"), style = MaterialTheme.typography.bodyMedium)
        val ev = d.optJSONArray("evidence") ?: JSONArray()
        for (i in 0 until ev.length()) {
            val e = ev.getJSONObject(i)
            Row(Modifier.padding(top = 8.dp).height(IntrinsicSize.Min)) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(StudioColors.Primary.copy(alpha = 0.5f)))
                Spacer(Modifier.width(8.dp))
                Text("“" + e.optString("quote") + "” — " + e.optString("title") + (if (!e.isNull("page") && e.has("page")) ", p." + e.optInt("page") else ""),
                    style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
            }
        }
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (editable && text.trim() != d.optString("text")) StudioButton("Save edit", { vm.act { vm.features.editMemory(m.id, text) } }, variant = BtnVariant.Outline)
            if (proposed) StudioButton("Approve", {
                vm.act { if (text.trim() != d.optString("text")) vm.features.editMemory(m.id, text); vm.features.approveMemory(m.id); vm.report("Added to Creative DNA") }
            }, icon = Lucide.Check)
            StudioButton(if (proposed) "Reject" else "Remove", { vm.act { vm.features.removeRecord("memories", m.id) } }, variant = BtnVariant.Ghost)
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent
