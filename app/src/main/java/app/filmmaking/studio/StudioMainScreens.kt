package app.filmmaking.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.ui.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * The main Studio screens, rebuilt from the web app (screenshot-match src/routes: index, ideas.*, projects.*,
 * library) so the phone and the web read the same: same words, same order, same components.
 */

internal val PROJECT_STATUSES = listOf("Idea", "Development", "Pre-production", "Ready to Shoot", "Shooting", "Review", "Handoff Ready", "Archived")
internal val PROJECT_FORMATS = listOf("Reel", "Short Film", "YouTube Film", "Documentary", "Experimental")
internal val NARRATIVE_APPROACHES = listOf("Voiceover essay", "Observational", "Montage", "Scripted", "Documentary", "Hybrid", "Experimental")
internal val ASPECT_RATIOS = listOf("9:16", "4:5", "1:1", "16:9", "2.39:1")
internal val IDEA_STATUSES = listOf("Inbox", "Exploring", "Ready", "Converted")
internal val PROJECT_TABS = listOf("Overview", "Develop", "Story", "Visual", "Shots", "Planner", "Shoot", "Wrap")

internal class KindMeta(val label: String, val icon: ImageVector, val placeholder: String)

internal val KIND_META: Map<String, KindMeta> by lazy {
    linkedMapOf(
        "text" to KindMeta("Text", Lucide.Type, "Just write it down…"),
        "voice" to KindMeta("Voice", Lucide.Mic, "What is the voice note about?"),
        "image" to KindMeta("Image", Lucide.Image, "What do you see in it?"),
        "link" to KindMeta("Link", Lucide.Link2, "Why does this link matter?"),
        "quote" to KindMeta("Quote", Lucide.Quote, "A line, a fragment of dialogue…"),
        "question" to KindMeta("Question", Lucide.CircleQuestionMark, "What are you wondering?"),
        "observation" to KindMeta("Seen", Lucide.Eye, "Something you noticed today…"),
        "sound" to KindMeta("Sound", Lucide.Music, "A sound or piece of music…"),
    )
}

private fun kindOf(idea: JSONObject) = KIND_META[idea.optString("kind")] ?: KIND_META.getValue("text")

internal fun fmtDate(iso: String, pattern: String): String = runCatching {
    DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(Instant.parse(iso).atZone(ZoneId.systemDefault()))
}.getOrDefault("")

private fun JSONArray?.objects(): List<JSONObject> =
    this?.let { a -> (0 until a.length()).mapNotNull { i -> a.optJSONObject(i) ?: a.optString(i).takeIf { it.isNotBlank() }?.let { JSONObject().put("label", it) } } } ?: emptyList()

private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

/** A small, downsampled bitmap from a file:// image kept on this device. */
@Composable
private fun rememberFileImage(uri: String?, maxPx: Int = 480): ImageBitmap? = remember(uri) {
    val path = uri?.takeIf { it.startsWith("file://") }?.let { android.net.Uri.parse(it).path } ?: return@remember null
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}

private fun firstImage(idea: JSONObject) = idea.optJSONArray("attachments").objects().firstOrNull { it.optString("kind") == "image" }?.optString("uri")

// ---------------------------------------------------------------- progress (web projectProgress)

internal class FilmProgress(
    val script: Int, val visual: Int, val shots: Int, val planner: Int, val shoot: Int, val wrap: Int,
    val plannedShots: Int, val totalShots: Int, val covered: Int, val needLighting: Int,
) {
    val average get() = (script + visual + shots + planner + shoot + wrap) / 6
}

internal fun filmProgress(projectId: String, shots: List<StudioRecord>, takes: List<StudioRecord>, items: List<StudioRecord>): FilmProgress {
    fun pct(a: Int, b: Int) = if (b == 0) 0 else Math.round(a * 100f / b)
    val mine = shots.filter { data(it).optString("projectId") == projectId }
    val ids = mine.map { it.id }.toSet()
    val shotTakes = takes.map { data(it) }.filter { it.optString("shotId") in ids }
    val work = items.filter { it.deletedAt == null && data(it).optString("projectId") == projectId }
    fun lighting(s: StudioRecord) = data(s).optJSONObject("lighting")?.optString("source").orEmpty()
    val planned = mine.count {
        val d = data(it)
        d.optString("description").isNotBlank() && d.optJSONObject("camera")?.optString("lens").orEmpty().isNotBlank() && lighting(it).isNotBlank()
    }
    val covered = mine.count { s -> shotTakes.any { it.optString("shotId") == s.id && it.optString("quality") != "unusable" } }
    val preferred = mine.count { s -> shotTakes.any { it.optString("shotId") == s.id && it.optBoolean("preferred") } }
    val scripts = work.filter { it.collection == "artifacts" }
    val boarded = work.filter { it.collection == "storyboards" }.map { data(it).optString("shotId") }.toSet()
    val checklist = work.filter { it.collection == "checklist" }
    return FilmProgress(
        script = pct(scripts.count { data(it).optString("draft").isNotBlank() }, scripts.size.coerceAtLeast(1)),
        visual = pct(mine.count { it.id in boarded }, mine.size),
        shots = pct(planned, mine.size),
        planner = pct(checklist.count { data(it).optString("status") == "ready" }, checklist.size),
        shoot = pct(covered, mine.size),
        wrap = pct(preferred, mine.size),
        plannedShots = planned, totalShots = mine.size, covered = covered,
        needLighting = mine.count { data(it).optString("status") != "complete" && lighting(it).isBlank() },
    )
}

private fun lastOpened(p: StudioRecord) = data(p).optString("lastOpenedAt").ifBlank { data(p).optString("updatedAt") }

// ---------------------------------------------------------------- Home

@Composable
internal fun HomeScreen(
    ideas: List<StudioRecord>, projects: List<StudioRecord>, shots: List<StudioRecord>, takes: List<StudioRecord>,
    items: List<StudioRecord>, onCapture: () -> Unit, openProject: (String, String) -> Unit, openIdea: (String) -> Unit,
    goTab: (String) -> Unit, toggleChecklist: (String) -> Unit, openCamera: () -> Unit,
) {
    val active = projects.filter { data(it).optString("status") != "Archived" }.sortedByDescending { lastOpened(it) }
    val last = active.firstOrNull()
    val prog = last?.let { filmProgress(it.id, shots, takes, items) }
    val activeIds = active.map { it.id }.toSet()
    val tasks = items.filter { it.collection == "checklist" && it.deletedAt == null && data(it).optString("status") != "ready" && data(it).optString("projectId") in activeIds }.take(4)
    val nextAction = when {
        prog == null -> ""
        prog.totalShots == 0 -> "No shots yet. Break the film into scenes and shots."
        prog.needLighting > 0 -> "${prog.plannedShots} of ${prog.totalShots} shots planned. Finish your lighting setups."
        prog.shoot < 100 -> "${prog.covered} of ${prog.totalShots} shots covered. Plan the next shoot."
        else -> "All shots covered. Prepare the handoff."
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 16.dp).padding(top = 24.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column {
            Eyebrow("Studio · Personal")
            Spacer(Modifier.height(4.dp))
            Text("Good day, director.", style = MaterialTheme.typography.displaySmall)
        }
        QuickCaptureBar(onCapture)
        if (last != null && prog != null) NextActionHero(last, nextAction, prog.shots) { openProject(last.id, "Overview") }

        Column {
            SectionTitle("Active films", "All") { goTab("Projects") }
            if (active.isEmpty()) EmptyState("No films yet", "Start one from an idea, or create a project.") {
                StudioButton("New project", { goTab("Projects") }, variant = BtnVariant.Outline, icon = Lucide.Plus)
            } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(active, key = { it.id }) { p ->
                    val d = data(p)
                    Column(Modifier.width(176.dp).clip(RoundedCornerShape(18.dp)).background(StudioColors.Card)
                        .border(1.dp, StudioColors.Border, RoundedCornerShape(18.dp)).clickable { openProject(p.id, "Overview") }.padding(12.dp)) {
                        Eyebrow(d.optString("format"))
                        Text(p.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.optString("status"), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                        GoldProgress(filmProgress(p.id, shots, takes, items).average.toFloat())
                    }
                }
            }
        }

        Column {
            SectionTitle("Shoot prep")
            if (tasks.isEmpty()) EmptyState("Nothing pending", "Your shoot prep is clear.")
            else StudioCard(padding = 0.dp) {
                tasks.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = StudioColors.Border)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Checkbox) { toggleChecklist(t.id) }.padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Lucide.Circle, "Mark ready", Modifier.size(20.dp), tint = StudioColors.MutedForeground)
                        Text(t.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Eyebrow(data(t).optString("category"))
                    }
                }
            }
            if (last != null && prog != null && prog.needLighting > 0) Row(Modifier.padding(top = 12.dp).heightIn(min = 40.dp)
                .clickable { openProject(last.id, "Shots") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Lucide.CircleCheckBig, null, Modifier.size(16.dp), tint = StudioColors.Primary)
                Text("${prog.needLighting} shots need lighting plans", style = MaterialTheme.typography.bodyMedium, color = StudioColors.Primary)
                Icon(Lucide.ArrowRight, null, Modifier.size(16.dp), tint = StudioColors.Primary)
            }
        }

        Column {
            SectionTitle("Recent ideas", "Vault") { goTab("Ideas") }
            if (ideas.isEmpty()) EmptyState("No ideas yet", "Capture anything — it doesn't need structure.")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ideas.sortedByDescending { data(it).optString("createdAt") }.take(3).forEach { i ->
                    val d = data(i)
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(StudioColors.Card)
                        .border(1.dp, StudioColors.Border, RoundedCornerShape(18.dp)).clickable { openIdea(i.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(i.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(d.optString("original"), style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            StudioChip(d.optString("status"))
                            Text(fmtDate(d.optString("createdAt"), "d MMM"), Modifier.padding(top = 4.dp), style = MonoStyle.copy(fontSize = 10.sp), color = StudioColors.MutedForeground)
                        }
                    }
                }
            }
        }

        StudioCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(StudioColors.GoldSoft), contentAlignment = Alignment.Center) {
                    Icon(Lucide.Camera, null, Modifier.size(22.dp), tint = StudioColors.Primary)
                }
                Column(Modifier.weight(1f)) {
                    Eyebrow("Duoport camera")
                    Text("Film front and back at once", style = MaterialTheme.typography.titleMedium)
                }
            }
            Text("Recording is built into this app. Film a planned shot from its shot page, or open the camera freely.",
                Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodyMedium, color = StudioColors.MutedForeground)
            StudioButton("Open Duoport camera", openCamera, Modifier.fillMaxWidth(), BtnVariant.Outline, icon = Lucide.Video)
        }
    }
}

@Composable
private fun QuickCaptureBar(onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(StudioColors.GoldSoft).drawBehind {
        drawRoundRect(StudioColors.Primary.copy(alpha = 0.4f), cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
    }.clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Lucide.Plus, null, Modifier.size(20.dp), tint = StudioColors.Primary)
        Text("Capture a thought, image, sound…", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = StudioColors.MutedForeground)
        Eyebrow("Quick capture", color = StudioColors.Primary)
    }
}

@Composable
private fun NextActionHero(project: StudioRecord, nextAction: String, shotsPct: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().height(240.dp).shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
        .background(Brush.linearGradient(listOf(StudioColors.Accent, StudioColors.Card, StudioColors.Background)))
        .border(1.dp, StudioColors.Border, shape).clickable(onClick = onClick)) {
        Icon(Lucide.Clapperboard, null, Modifier.align(Alignment.TopEnd).padding(20.dp).size(120.dp), tint = StudioColors.Primary.copy(alpha = 0.07f))
        Box(Modifier.matchParentSize().background(StudioColors.Scrim))
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Next action · " + data(project).optString("status"), color = StudioColors.Primary)
            Text(project.title, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(nextAction, style = MaterialTheme.typography.bodyMedium, color = StudioColors.Foreground.copy(alpha = 0.85f))
            GoldProgress(shotsPct.toFloat())
        }
    }
}

// ---------------------------------------------------------------- Ideas

@Composable
internal fun IdeasScreen(ideas: List<StudioRecord>, onCapture: () -> Unit, openIdea: (String) -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    var q by rememberSaveable { mutableStateOf("") }
    val list = ideas.sortedByDescending { data(it).optString("createdAt") }.filter {
        val d = data(it)
        (filter == "All" || d.optString("status") == filter) &&
            (it.title + " " + d.optString("original") + " " + d.optJSONArray("tags").strings().joinToString(" ")).lowercase().contains(q.trim().lowercase())
    }
    Column(Modifier.fillMaxSize()) {
        StudioTopBar("Idea Vault", "${ideas.size} ideas") { IconBtn(Lucide.Plus, "Capture idea", onCapture, active = true) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { StudioSearch(q, { q = it }, "Search ideas and tags") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("All") + IDEA_STATUSES).forEach { s ->
                        val n = if (s == "All") ideas.size else ideas.count { data(it).optString("status") == s }
                        StudioChip("$s · $n", active = filter == s, onClick = { filter = s })
                    }
                }
            }
            items(list, key = { it.id }) { i ->
                val d = data(i)
                val k = kindOf(d)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(StudioColors.Card)
                    .border(1.dp, StudioColors.Border, RoundedCornerShape(18.dp)).clickable { openIdea(i.id) }.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val img = rememberFileImage(firstImage(d), 240)
                    Box(Modifier.size(80.dp).clip(RoundedCornerShape(10.dp)).background(StudioColors.Surface), contentAlignment = Alignment.Center) {
                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Icon(k.icon, null, Modifier.size(24.dp), tint = StudioColors.Primary)
                    }
                    Column(Modifier.weight(1f)) {
                        Eyebrow(k.label + " · " + d.optString("status"))
                        Text(i.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(d.optString("original"), style = MaterialTheme.typography.bodyMedium, color = StudioColors.MutedForeground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (list.isEmpty()) item {
                EmptyState("No ideas here", "Capture anything — it doesn't need structure.") {
                    StudioButton("Capture an idea", onCapture, icon = Lucide.Plus)
                }
            }
        }
    }
}

/** The web CaptureSheet: pick a kind, write it down, save to Inbox. */
@Composable
internal fun CaptureSheet(vm: StudioViewModel, onClose: () -> Unit, onSaved: (String) -> Unit) {
    var kind by rememberSaveable { mutableStateOf("text") }
    var text by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var tags by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf("") }
    StudioSheet("Capture", onClose) {
        KIND_META.entries.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, m) ->
                    val on = k == kind
                    Column(Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (on) StudioColors.GoldSoft else StudioColors.Surface)
                        .border(1.dp, if (on) StudioColors.Primary else StudioColors.Border, RoundedCornerShape(12.dp))
                        .clickable(role = Role.RadioButton) { kind = k }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(m.icon, null, Modifier.size(20.dp), tint = if (on) StudioColors.Primary else StudioColors.MutedForeground)
                        Spacer(Modifier.height(4.dp))
                        Text(m.label, style = MaterialTheme.typography.labelMedium, color = if (on) StudioColors.Primary else StudioColors.MutedForeground)
                    }
                }
            }
        }
        if (kind == "voice" || kind == "image") Text(
            if (kind == "voice") "Save the idea first, then record the voice note on it." else "Save the idea first, then add the photo from this phone on it.",
            style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        StudioField("Your idea", text, { text = it }, placeholder = KIND_META.getValue(kind).placeholder, minLines = 4)
        StudioField("Title (optional)", title, { title = it })
        StudioField("Tags", tags, { tags = it }, placeholder = "ritual, light, family")
        if (kind == "link" || kind == "quote" || kind == "sound") StudioField("Source / URL", source, { source = it })
        StudioButton("Save to Inbox", {
            vm.captureIdea(text, kind, title, tags.split(",").map { it.trim() }.filter { it.isNotEmpty() }, source) { id ->
                text = ""; title = ""; tags = ""; source = ""
                onSaved(id)
            }
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() || title.isNotBlank())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun IdeaDetailScreen(
    vm: StudioViewModel, idea: StudioRecord, onBack: () -> Unit, openProject: (String) -> Unit,
    onPickImage: () -> Unit, recording: Boolean, onToggleVoice: () -> Unit,
) {
    val d = data(idea)
    val k = kindOf(d)
    val status = d.optString("status")
    val projectId = d.optString("projectId").takeIf { it.isNotBlank() }
    var link by rememberSaveable(idea.id) { mutableStateOf("") }
    var thought by rememberSaveable(idea.id + "x") { mutableStateOf("") }
    val attachments = d.optJSONArray("attachments").objects()
    val sources = d.optJSONArray("sources").objects()
    val expansions = d.optJSONArray("expansions").objects()
    val tags = d.optJSONArray("tags").strings().filter { it.isNotBlank() }
    Column(Modifier.fillMaxSize()) {
        StudioTopBar(idea.title, k.label + " · " + status, onBack) {
            IconBtn(Lucide.Trash2, "Delete idea", { vm.act { vm.features.removeRecord("ideas", idea.id) }; onBack() })
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rememberFileImage(firstImage(d), 1080)?.let {
                Image(it, null, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
            }
            StudioCard {
                Eyebrow("Original capture · preserved")
                Spacer(Modifier.height(8.dp))
                Text(d.optString("original"), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 28.sp))
                Text("Created " + fmtDate(d.optString("createdAt"), "d MMM yyyy, HH:mm"), Modifier.padding(top = 12.dp),
                    style = MonoStyle.copy(fontSize = 11.sp), color = StudioColors.MutedForeground)
            }
            if (tags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { StudioChip("#$it") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Status")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status == "Converted") StudioChip("Converted", active = true)
                    else listOf("Inbox", "Exploring", "Ready").forEach { s -> StudioChip(s, active = status == s, onClick = { vm.setIdeaStatus(idea.id, s) }) }
                }
            }
            if (projectId != null) StudioButton("Open film", { openProject(projectId) }, Modifier.fillMaxWidth(), icon = Lucide.Clapperboard)
            else StudioButton("Develop as film", { vm.convertIdea(idea.id) { openProject(it) } }, Modifier.fillMaxWidth(), icon = Lucide.Clapperboard)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionTitle("Add to this idea")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioButton("Add image", onPickImage, Modifier.weight(1f), BtnVariant.Outline, icon = Lucide.Image)
                    StudioButton(if (recording) "Stop voice note" else "Record voice note", onToggleVoice, Modifier.weight(1f),
                        if (recording) BtnVariant.Danger else BtnVariant.Outline, icon = Lucide.Mic)
                }
                StudioField("Link (https://…)", link, { link = it })
                StudioButton("Attach link", { vm.act { vm.features.attachToIdea(idea.id, "link", link.trim()) }; link = "" },
                    Modifier.fillMaxWidth(), BtnVariant.Outline, enabled = link.isNotBlank(), icon = Lucide.Link2)
                StudioField("Add a thought (original stays unchanged)", thought, { thought = it }, minLines = 2)
                StudioButton("Add to idea", { vm.act { vm.features.expandIdea(idea.id, thought.trim()) }; thought = "" },
                    Modifier.fillMaxWidth(), BtnVariant.Outline, enabled = thought.isNotBlank())
            }
            if (attachments.isNotEmpty() || sources.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Attached")
                (attachments + sources).forEach { a ->
                    val kind = a.optString("kind")
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(StudioColors.Card)
                        .border(1.dp, StudioColors.Border, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(when (kind) { "image" -> Lucide.Image; "voice" -> Lucide.Mic; "link" -> Lucide.Link2; else -> Lucide.Quote }, null,
                            Modifier.size(18.dp), tint = StudioColors.Primary)
                        Text(when (kind) {
                            "link" -> a.optString("uri").removePrefix("https://").removePrefix("http://").take(80)
                            "voice" -> "Voice note · " + fmtDate(a.optString("addedAt"), "d MMM HH:mm")
                            "image" -> "Photo · kept on this device"
                            else -> a.optString("label")
                        }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (expansions.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Expansions (original stays untouched)")
                expansions.forEach { e ->
                    StudioCard {
                        Text(e.optString("text"), style = MaterialTheme.typography.bodyMedium)
                        Text(e.optString("author", "user") + " · " + fmtDate(e.optString("at"), "d MMM HH:mm"), Modifier.padding(top = 8.dp),
                            style = MonoStyle.copy(fontSize = 10.sp), color = StudioColors.MutedForeground)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Projects

@Composable
internal fun ProjectsScreen(
    projects: List<StudioRecord>, shots: List<StudioRecord>, takes: List<StudioRecord>, items: List<StudioRecord>,
    onNew: () -> Unit, open: (String) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf("Active") }
    val list = projects.sortedByDescending { lastOpened(it) }.filter {
        val st = data(it).optString("status")
        filter == "All" || (if (filter == "Active") st != "Archived" else st == filter)
    }
    Column(Modifier.fillMaxSize()) {
        StudioTopBar("Projects", "${projects.size} films") { IconBtn(Lucide.Plus, "New project", onNew, active = true) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf("Active", "All") + PROJECT_STATUSES).forEach { st -> StudioChip(st, active = filter == st, onClick = { filter = st }) }
                }
            }
            items(list, key = { it.id }) { p ->
                val d = data(p)
                val pr = filmProgress(p.id, shots, takes, items)
                StudioCard(Modifier.fillMaxWidth(), onClick = { open(p.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow(d.optString("format") + " · " + d.optString("aspectRatio"), Modifier.weight(1f))
                        StudioChip(d.optString("status"), tone = ChipTone.Gold)
                    }
                    Text(p.title, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
                    if (d.optString("logline").isNotBlank()) Text(d.optString("logline"), Modifier.padding(top = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, color = StudioColors.MutedForeground)
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GoldProgress(pr.shoot.toFloat(), Modifier.weight(1f))
                        Text("${pr.covered}/${pr.totalShots} shots covered", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                    }
                }
            }
            if (list.isEmpty()) item {
                EmptyState("No films here", "Create a project, or develop one from an idea.") {
                    StudioButton("New project", onNew, icon = Lucide.Plus)
                }
            }
        }
    }
}

@Composable
internal fun NewProjectSheet(vm: StudioViewModel, onClose: () -> Unit, onCreated: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    StudioSheet("New project", onClose) {
        StudioField("Working title", name, { name = it }, placeholder = "The Rain Film")
        StudioButton("Create", { vm.createProject(name) { id -> name = ""; onCreated(id) } }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
    }
}

/** Project overview tab (web projects.$id.index). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProjectOverview(vm: StudioViewModel, project: StudioRecord, progress: FilmProgress, openTab: (String) -> Unit) {
    val d = data(project)
    val id = project.id
    var name by rememberSaveable(id) { mutableStateOf(d.optString("name").ifBlank { project.title }) }
    var logline by rememberSaveable(id + "l") { mutableStateOf(d.optString("logline")) }
    var concept by rememberSaveable(id + "c") { mutableStateOf(d.optString("concept")) }
    var duration by rememberSaveable(id + "t") { mutableStateOf(d.optInt("targetDurationSec", 60).toString()) }
    var alt by rememberSaveable(id + "a") { mutableStateOf(d.optJSONArray("altFormats").strings().joinToString(", ")) }
    var mood by rememberSaveable(id + "m") { mutableStateOf(d.optString("mood")) }
    var goals by rememberSaveable(id + "g") { mutableStateOf(d.optString("goals")) }
    var notes by rememberSaveable(id + "n") { mutableStateOf(d.optString("notes")) }
    var decision by rememberSaveable(id + "d") { mutableStateOf("") }
    val status = d.optString("status")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Eyebrow("Status — move freely")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROJECT_STATUSES.forEach { st ->
                    StudioChip(st, active = status == st, onClick = {
                        if (st != status) { vm.updateProject(id, "status" to st); vm.addDecision(id, "Status → $st") }
                    })
                }
            }
        }
        Column {
            SectionTitle("Workspaces")
            val ws = listOf("Story & Script" to (progress.script to "Story"), "Visual Direction" to (progress.visual to "Visual"),
                "Shot & Scene Studio" to (progress.shots to "Shots"), "Production Planner" to (progress.planner to "Planner"),
                "Shoot Manager" to (progress.shoot to "Shoot"), "Wrap & Handoff" to (progress.wrap to "Wrap"))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ws.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (label, v) ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(StudioColors.Card)
                                .border(1.dp, StudioColors.Border, RoundedCornerShape(18.dp)).clickable { openTab(v.second) }.padding(12.dp)) {
                                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    GoldProgress(v.first.toFloat(), Modifier.weight(1f))
                                    Text("${v.first}%", style = MonoStyle.copy(fontSize = 11.sp), color = StudioColors.MutedForeground)
                                }
                            }
                        }
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Concept")
            StudioField("Project name", name, { name = it; if (it.isNotBlank()) vm.updateProject(id, "name" to it.trim()) })
            StudioField("Logline", logline, { logline = it; vm.updateProject(id, "logline" to it) }, minLines = 2)
            StudioField("Concept", concept, { concept = it; vm.updateProject(id, "concept" to it) }, minLines = 3)
            Expandable("Format & delivery", d.optString("format") + " · " + d.optString("aspectRatio") + " · " + d.optInt("targetDurationSec") + "s") {
                StudioSelect("Format", d.optString("format"), PROJECT_FORMATS, { vm.updateProject(id, "format" to it) })
                StudioSelect("Narrative approach", d.optString("narrativeApproach"), NARRATIVE_APPROACHES, { vm.updateProject(id, "narrativeApproach" to it) })
                StudioField("Target duration (seconds)", duration, { v ->
                    duration = v.filter(Char::isDigit).take(5); vm.updateProject(id, "targetDurationSec" to (duration.toIntOrNull() ?: 0))
                }, numeric = true)
                StudioSelect("Primary aspect ratio", d.optString("aspectRatio"), ASPECT_RATIOS, { vm.updateProject(id, "aspectRatio" to it) })
                StudioField("Alternative formats", alt, { v -> alt = v; vm.updateProject(id, "altFormats" to v.split(",").map { it.trim() }.filter { it.isNotEmpty() }) },
                    placeholder = "9:16 cut-down, 1:1 teaser")
            }
            Expandable("Intention", mood.ifBlank { "Mood, goals, notes" }) {
                StudioField("Mood / emotional intention", mood, { mood = it; vm.updateProject(id, "mood" to it) })
                StudioField("Creative goals", goals, { goals = it; vm.updateProject(id, "goals" to it) }, minLines = 3)
                StudioField("References & notes", notes, { notes = it; vm.updateProject(id, "notes" to it) }, minLines = 3)
            }
        }
        Column {
            SectionTitle("Decision log")
            Row(Modifier.padding(bottom = 16.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { StudioSearchless(decision) { decision = it } }
                StudioButton("Add", { if (decision.isNotBlank()) { vm.addDecision(id, decision.trim()); decision = "" } }, enabled = decision.isNotBlank())
            }
            val decisions = d.optJSONArray("decisions").objects().reversed()
            if (decisions.isEmpty()) Text("Status changes and choices you record appear here.", style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
            Column(Modifier.drawBehind {
                drawLine(StudioColors.Primary.copy(alpha = 0.3f), androidx.compose.ui.geometry.Offset(4.dp.toPx(), 0f),
                    androidx.compose.ui.geometry.Offset(4.dp.toPx(), size.height), 1.dp.toPx())
            }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                decisions.forEach { e ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(StudioColors.Primary))
                        Column {
                            Text(fmtDate(e.optString("at"), "d MMM yyyy"), style = MonoStyle.copy(fontSize = 11.sp), color = StudioColors.MutedForeground)
                            Text(e.optString("text"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        StudioCard {
            Text("Created " + fmtDate(d.optString("createdAt"), "d MMM yyyy") + " · ID " + id.take(8),
                style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
        }
    }
}

/** A bare field (no eyebrow) for inline rows such as the decision log. */
@Composable
private fun StudioSearchless(value: String, onChange: (String) -> Unit) {
    androidx.compose.material3.OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text("Record a creative decision", color = StudioColors.MutedForeground.copy(alpha = 0.7f)) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), shape = RoundedCornerShape(12.dp),
        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
            focusedContainerColor = StudioColors.Surface, unfocusedContainerColor = StudioColors.Surface,
            focusedBorderColor = StudioColors.Ring, unfocusedBorderColor = StudioColors.Input, cursorColor = StudioColors.Primary,
        ))
}

/** Shots tab: scenes with their shots (web Shot & Scene Studio). */
@Composable
internal fun ShotsTab(
    scenes: List<StudioRecord>, shots: List<StudioRecord>, takes: List<StudioRecord>,
    addScene: (String) -> Unit, addShot: (String, String) -> Unit, openShot: (String) -> Unit,
) {
    var sceneName by rememberSaveable { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle("Scenes and shot list")
                Text("${scenes.size} scenes · ${shots.size} shots", style = MonoStyle.copy(fontSize = 12.sp), color = StudioColors.MutedForeground)
            }
        }
        item {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StudioField("Scene name", sceneName, { sceneName = it }, Modifier.weight(1f), placeholder = "Kitchen, early morning")
                StudioButton("Add scene", { addScene(sceneName); sceneName = "" }, enabled = sceneName.isNotBlank())
            }
        }
        if (scenes.isEmpty()) item { EmptyState("No scenes yet", "Add the first scene, then break it into shots.") }
        items(scenes.sortedBy { data(it).optInt("order") }, key = { it.id }) { scene ->
            var shotName by rememberSaveable(scene.id) { mutableStateOf("") }
            StudioCard(Modifier.fillMaxWidth()) {
                Eyebrow("Scene " + data(scene).optInt("number"))
                Text(scene.title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                Spacer(Modifier.height(8.dp))
                shots.filter { data(it).optString("sceneId") == scene.id }.forEach { shot ->
                    val sd = data(shot)
                    val taken = takes.count { data(it).optString("shotId") == shot.id }
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).clickable { openShot(shot.id) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(sd.optString("code"), Modifier.width(56.dp), style = MonoStyle.copy(fontSize = 12.sp), color = StudioColors.Primary)
                        Column(Modifier.weight(1f)) {
                            Text(shot.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(sd.optJSONObject("camera")?.optString("shotSize")?.takeIf { it.isNotBlank() },
                                if (taken > 0) "$taken take" + (if (taken == 1) "" else "s") else null).joinToString(" · ").ifBlank { "Not planned yet" },
                                style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
                        }
                        StudioChip(sd.optString("status"), tone = if (sd.optString("status") == "complete") ChipTone.Success else ChipTone.Default)
                        Icon(Lucide.ChevronRight, null, Modifier.size(16.dp), tint = StudioColors.MutedForeground)
                    }
                    HorizontalDivider(color = StudioColors.Border)
                }
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StudioField("Shot title", shotName, { shotName = it }, Modifier.weight(1f))
                    StudioButton("Add shot", { addShot(scene.id, shotName); shotName = "" }, variant = BtnVariant.Outline, enabled = shotName.isNotBlank())
                }
            }
        }
    }
}
