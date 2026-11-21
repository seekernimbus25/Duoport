package app.filmmaking.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.filmmaking.studio.data.ExternalCameraNaming
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.ui.*
import org.json.JSONObject

/**
 * One shot (web `projects/$id/shots/$shotId`): a framing strip, status, the record action, the plan editor,
 * external-camera filename log and the takes. External filenames are references only, never footage.
 */
@Composable
internal fun ShotScreen(
    shot: StudioRecord,
    takes: List<StudioRecord>,
    externalProjectFiles: List<StudioRecord>,
    changeStatus: (String, String) -> Unit,
    savePlan: (String, String, String, String, String, String, String, String, String, String, String) -> Unit,
    rateTake: (String, String) -> Unit,
    preferTake: (String) -> Unit,
    addExternalFile: (String) -> Unit,
    removeExternalFile: (String) -> Unit,
    vm: StudioViewModel,
    onShoot: () -> Unit
) {
    val plan = data(shot)
    val camera = plan.optJSONObject("camera") ?: JSONObject()
    val light = plan.optJSONObject("lighting") ?: JSONObject()
    val sound = plan.optJSONObject("sound") ?: JSONObject()
    val editorial = plan.optJSONObject("editorial") ?: JSONObject()

    var editing by rememberSaveable(shot.id) { mutableStateOf(false) }
    var name by rememberSaveable(shot.id) { mutableStateOf(shot.title) }
    var description by rememberSaveable(shot.id) { mutableStateOf(plan.optString("description")) }
    var voiceover by rememberSaveable(shot.id) { mutableStateOf(plan.optString("scriptPassage")) }
    var shotSize by rememberSaveable(shot.id) { mutableStateOf(camera.optString("shotSize")) }
    var angle by rememberSaveable(shot.id) { mutableStateOf(camera.optString("angle")) }
    var lens by rememberSaveable(shot.id) { mutableStateOf(camera.optString("lens")) }
    var fps by rememberSaveable(shot.id) { mutableStateOf(camera.optString("frameRate")) }
    var lightNotes by rememberSaveable(shot.id) { mutableStateOf(light.optString("source")) }
    var soundNotes by rememberSaveable(shot.id) { mutableStateOf(sound.optString("ambient")) }
    var editNotes by rememberSaveable(shot.id) { mutableStateOf(editorial.optString("editorNotes")) }

    val sequence = ExternalCameraNaming.nextFrom(externalProjectFiles)
    val externalForShot = externalProjectFiles.filter { data(it).optString("shotId") == shot.id }.sortedBy { it.updatedAt }
    var enterWholeName by rememberSaveable(shot.id) { mutableStateOf(sequence == null) }
    var filenameInput by rememberSaveable(shot.id) { mutableStateOf("") }
    var numberInput by rememberSaveable(shot.id) { mutableStateOf("") }
    val nextName = sequence?.let { parsed -> runCatching { parsed.filenameFor(numberInput.ifBlank { parsed.nextSuffix.orEmpty() }) }.getOrNull() }
    val sortedTakes = takes.sortedBy { data(it).optInt("number") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // Framing strip (the web hero's Meta row).
        Row(Modifier.fillMaxWidth().background(StudioColors.Surface).padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Meta("Size", camera.optString("shotSize"), Modifier.weight(1f))
            Meta("Angle", camera.optString("angle"), Modifier.weight(1f))
            Meta("Lens", camera.optString("lens").substringBefore(","), Modifier.weight(1f))
            Meta("FPS", camera.optString("frameRate").substringBefore(" "), Modifier.weight(1f))
        }
        StudioDivider()

        Column(Modifier.padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Eyebrow("Status")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("planned", "ready", "in progress", "skipped").forEach { status ->
                        StudioChip(status, active = plan.optString("status") == status, onClick = { changeStatus(shot.id, status) })
                    }
                }
                StudioButton("Record this shot in Duoport", onShoot, Modifier.fillMaxWidth(), icon = Lucide.Video)
                MutedText("Wait for Duoport's export to finish before returning. Actual footage is marked reported, " +
                    "never checksum-verified until checked.", small = true)
            }

            StudioSection("Shot plan") {
                if (!editing) StudioCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        PlanLine("Action / composition", plan.optString("description"))
                        PlanLine("Voiceover", plan.optString("scriptPassage"))
                        PlanLine("Lighting", light.optString("source"))
                        PlanLine("Sound", sound.optString("ambient"))
                        PlanLine("Editor", editorial.optString("editorNotes"))
                    }
                }
                StudioButton(if (editing) "Close shot editor" else "Edit camera · lighting · sound · script", { editing = !editing },
                    Modifier.fillMaxWidth(), variant = BtnVariant.Outline, icon = if (editing) Lucide.X else Lucide.Pencil)
                if (editing) {
                    Eyebrow("Creative", color = StudioColors.Primary)
                    StudioField("Shot title", name, { name = it })
                    StudioField("Action / composition", description, { description = it }, minLines = 3)
                    StudioField("Voiceover / teleprompter", voiceover, { voiceover = it }, minLines = 3)
                    Eyebrow("Camera", Modifier.padding(top = 8.dp), color = StudioColors.Primary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StudioField("Shot size", shotSize, { shotSize = it }, Modifier.weight(1f))
                        StudioField("Camera angle", angle, { angle = it }, Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        StudioField("Lens", lens, { lens = it }, Modifier.weight(1f))
                        StudioField("Frame rate", fps, { fps = it }, Modifier.weight(1f))
                    }
                    Eyebrow("Light, sound & edit", Modifier.padding(top = 8.dp), color = StudioColors.Primary)
                    StudioField("Lighting notes", lightNotes, { lightNotes = it }, minLines = 2)
                    StudioField("Sound notes", soundNotes, { soundNotes = it }, minLines = 2)
                    StudioField("Editor instructions", editNotes, { editNotes = it }, minLines = 2)
                    StudioButton("Save planned shot offline", {
                        savePlan(shot.id, name, description, voiceover, shotSize, angle, lens, fps, lightNotes, soundNotes, editNotes)
                        editing = false
                    }, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), icon = Lucide.Save)
                }
            }

            StudioSection("External camera takes", "Filming on DJI, Sony or another camera? Log the file name that camera prints. " +
                "The pattern carries across scenes and shots. These are references only — footage stays on the card until imported.") {
                StudioCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!enterWholeName && sequence != null) {
                            Eyebrow("Detected pattern: " + sequence.prefix + "[number]" + sequence.extension, color = StudioColors.Primary)
                            StudioField("File number only (suffix)", numberInput, { numberInput = it.filter(Char::isDigit).take(12) },
                                placeholder = sequence.nextSuffix ?: "0001", numeric = true)
                            if (nextName != null) Text("Filename: $nextName", style = MonoStyle)
                            StudioButton("Log external camera take", {
                                if (nextName != null) { addExternalFile(nextName); numberInput = "" }
                            }, Modifier.fillMaxWidth(), enabled = nextName != null, icon = Lucide.Plus)
                            StudioButton("Different camera or naming pattern", { enterWholeName = true; filenameInput = "" },
                                Modifier.fillMaxWidth(), variant = BtnVariant.Ghost)
                        } else {
                            StudioField("Camera filename (first entry)", filenameInput, { filenameInput = it.take(160) }, placeholder = "DJI_0045.MP4")
                            StudioButton("Log external camera take", {
                                addExternalFile(filenameInput); filenameInput = ""; enterWholeName = false; numberInput = ""
                            }, Modifier.fillMaxWidth(), enabled = filenameInput.trim().isNotBlank(), icon = Lucide.Plus)
                            if (sequence != null) StudioButton("Use detected camera sequence", { enterWholeName = false },
                                Modifier.fillMaxWidth(), variant = BtnVariant.Ghost)
                        }
                    }
                }
                Eyebrow("External references on this shot: " + externalForShot.size)
                externalForShot.forEach { reference ->
                    StudioCard(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(reference.title, style = MonoStyle)
                                MutedText("Reference only · footage not yet imported", small = true)
                            }
                            StudioButton("Remove", { removeExternalFile(reference.id) }, variant = BtnVariant.Ghost)
                        }
                    }
                }
            }

            StudioSection("Recorded takes: " + takes.size) {
                if (takes.isEmpty()) EmptyState("No takes yet", "No takes have been imported yet. Record in Duoport and they appear here.")
                sortedTakes.forEach { take ->
                    val t = data(take)
                    val preferred = t.optBoolean("preferred")
                    StudioCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Take " + t.optInt("number") + if (preferred) " · Preferred" else "", Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp))
                                if (preferred) StudioChip("Preferred", tone = ChipTone.Gold, leading = Lucide.Star)
                            }
                            Eyebrow(t.optString("capturedAt").take(16).replace("T", " · "))
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("review", "usable", "unusable").forEach { quality ->
                                    StudioChip(quality, active = t.optString("quality") == quality, onClick = { rateTake(take.id, quality) })
                                }
                            }
                            t.optJSONObject("settings")?.takeIf { it.length() > 0 }?.let { st ->
                                MutedText("Actual: " + listOf("frameRate", "resolution", "codec", "lens").mapNotNull { k -> st.optString(k).takeIf { it.isNotBlank() } }
                                    .joinToString(" · ") + " (" + st.optString("source") + ")", small = true)
                            }
                            if (!preferred) StudioButton("Choose as preferred take", { preferTake(take.id) }, variant = BtnVariant.Outline, icon = Lucide.Star)
                        }
                    }
                }
            }
            StudioDivider()
            TakeExtras(vm, shot, sortedTakes)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PlanLine(label: String, value: String) {
    Column(Modifier.fillMaxWidth()) {
        Eyebrow(label)
        Text(value.ifBlank { "Not planned yet" }, style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) StudioColors.MutedForeground else StudioColors.Foreground)
    }
}
