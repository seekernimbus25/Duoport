package app.filmmaking.studio

import android.os.Bundle
import android.net.Uri
import android.content.ContentResolver
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ActivityNotFoundException
import android.content.Intent
import com.dualframe.camera.MainActivity as DuoportMainActivity
import com.dualframe.camera.StudioDiagnostics
import androidx.activity.compose.rememberLauncherForActivityResult
import app.filmmaking.studio.camera.DuoportCaptureContract
import app.filmmaking.studio.camera.StudioCaptureImporter
import app.studio.contracts.duoport.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import app.filmmaking.studio.data.*
import app.filmmaking.studio.sync.StudioSync
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/** A functional local-first native slice: ideas -> film -> scenes -> shot list -> AI. */
class StudioViewModel(
    private val repo: StudioRepository,
    private val endpoint: ApiEndpoint,
    private val auth: StudioAuthClient,
    private val ai: StudioAiClient,
    private val importer: StudioCaptureImporter,
    private val workflow: ProjectWorkflowRepository,
    private val exporter: StudioExport,
    private val creative: StudioCreativeClient,
    private val verifier: MediaVerification,
    private val externalCamera: ExternalCameraLog,
    val features: FeatureRepository,
    private val mediaImport: ExternalMediaImport,
    val cloud: StudioSync,
    private val appContext: android.content.Context
) : ViewModel() {
    val ideas = repo.ideas().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val projects = repo.projects().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val scenes = repo.scenes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val shots = repo.shots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val takes = repo.takes().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val workflowItems = workflow.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var notice by mutableStateOf("")
        private set
    var apiStatus by mutableStateOf("")
        private set
    var session by mutableStateOf<StudioAuthClient.Session?>(cloud.sessions.load())
        private set
    var syncing by mutableStateOf(false)
        private set
    var syncSummary by mutableStateOf(cloud.lastSummary)
        private set
    var conflicts by mutableStateOf<List<app.filmmaking.studio.sync.SyncOp.Conflict>>(emptyList())
        private set
    var aiBusy by mutableStateOf(false)
        private set
    var conversationId by mutableStateOf<String?>(null)
    var archiveResults by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var importing by mutableStateOf(false)
        private set
    var signingIn by mutableStateOf(false)
        private set
    var sending by mutableStateOf(false)
    var verifying by mutableStateOf(false)
        private set
    var generating by mutableStateOf(false)
        private set
    var generatedOptions by mutableStateOf<GeneratedOptions?>(null)
        private set
    var chat by mutableStateOf<List<AiTurn>>(emptyList())
        private set
    var lastCost by mutableStateOf<Double?>(null)
        private set
    val apiUrl get() = endpoint.baseUrl

    init { if (session != null) StudioSync.schedule(appContext) }

    /** A valid access token (refreshed when close to expiry), or null when signed out. */
    private suspend fun token(): String? = try {
        cloud.sessions.fresh()?.also { session = it }?.accessToken
    } catch (e: AuthException) {
        if (cloud.sessions.load() == null) session = null
        throw IllegalStateException("Session expired: sign in again")
    }

    fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() } catch (e: Exception) { notice = e.message ?: "Operation failed" }
        }
    }
    fun report(message: String) { notice = message }
    fun captureIdea(text: String) = act {
        if (text.isNotBlank()) { repo.captureIdea(text.trim()); notice = "Idea saved on this device" }
    }
    fun convertIdea(id: String) = act { repo.convertIdea(id); notice = "Project created" }
    fun createProject(title: String) = act { if (title.isNotBlank()) repo.createProject(title.trim()) }
    fun addScene(projectId: String, title: String) = act {
        if (title.isNotBlank()) repo.addScene(projectId, title.trim())
    }
    fun addShot(sceneId: String, title: String) = act {
        if (title.isNotBlank()) repo.addShot(sceneId, title.trim())
    }
    fun changeShot(shotId: String, status: String) = act { repo.setShotStatus(shotId, status) }
    fun saveShotPlan(
        shotId: String, title: String, description: String, passage: String,
        size: String, angle: String, lens: String, fps: String,
        lighting: String, sound: String, editorial: String
    ) = act {
        repo.saveShotPlan(shotId, title, description, passage, size, angle, lens, fps, lighting, sound, editorial)
        notice = "Planned shot saved offline"
    }
    fun rateTake(takeId: String, quality: String) = act {
        repo.rateTake(takeId, quality)
        notice = "Take rated " + quality
    }
    fun preferTake(takeId: String) = act {
        repo.preferTake(takeId)
        notice = "Preferred take updated"
    }
    fun logExternalCameraFilename(shotId: String, filename: String) = act {
        externalCamera.add(shotId, filename)
        notice = "External camera filename saved. Actual footage has not been imported."
    }
    fun removeExternalCameraReference(referenceId: String) = act {
        externalCamera.remove(referenceId)
        notice = "External camera reference removed; no footage was deleted."
    }
    fun createWorkflow(projectId: String, kind: WorkflowKind, title: String, detail: String, shotId: String?) = act {
        workflow.create(projectId, kind, title, detail, shotId)
        notice = kind.label + " saved offline"
    }
    fun updateWorkflow(collection: String, id: String, title: String, detail: String) = act {
        workflow.update(collection, id, title, detail)
        notice = "Changes saved offline"
    }
    fun approveTreatment(projectId: String, id: String) = act {
        workflow.approveTreatment(projectId, id)
        notice = "Treatment approved; previous approval superseded"
    }
    fun approveScript(id: String) = act {
        workflow.approveScript(id)
        notice = "Script version approved and preserved"
    }
    fun toggleChecklist(id: String) = act { workflow.toggleChecklist(id) }
    fun verifyRecordedMedia(projectId: String) {
        if (verifying) return
        verifying = true
        viewModelScope.launch {
            try {
                val result = verifier.verify(projectId)
                notice = result.verified.toString() + " recordings SHA-256 verified, " +
                    result.missing + " missing/unreadable, " + result.skipped + " simulated/skipped"
            } catch (error: Exception) {
                notice = error.message ?: "Media verification failed"
            } finally { verifying = false }
        }
    }
    fun clearGenerated() { generatedOptions = null }
    fun generateOptions(project: StudioRecord, op: String, instructions: String) {
        if (session == null) { notice = "Sign in to request AI proposals"; return }
        if (generating) return
        generating = true
        generatedOptions = null
        viewModelScope.launch {
            try {
                val token = token() ?: error("Sign in to request AI proposals")
                generatedOptions = creative.generate(token, project, op, instructions)
                notice = "Review the AI proposals; nothing has been saved yet"
            } catch (error: Exception) {
                notice = error.message ?: "AI generation failed"
            } finally { generating = false }
        }
    }
    fun acceptGenerated(index: Int) {
        val options = generatedOptions ?: return
        val proposal = options.items.getOrNull(index) ?: return
        viewModelScope.launch {
            try {
                workflow.acceptGenerated(options.projectId, options.op, proposal)
                val remaining = options.items.filterIndexed { i, _ -> i != index }
                generatedOptions = if (remaining.isEmpty()) null else options.copy(items = remaining)
                notice = "Accepted proposal saved as a draft, not approved"
            } catch (error: Exception) { notice = error.message ?: "Could not accept proposal" }
        }
    }
    fun backup(onReady: (String) -> Unit) = act {
        onReady(exporter.backup())
        notice = "Backup ready to save"
    }
    fun exportProduction(projectId: String, onReady: (String) -> Unit) = act {
        onReady(exporter.production(projectId))
        notice = "Production JSON ready to save"
    }
    fun restore(uri: Uri, resolver: ContentResolver) = act {
        val source = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Cannot open backup")
        }
        val count = exporter.restore(source)
        notice = count.toString() + " records imported (existing local records kept)"
    }

    fun importRecording(shotId: String, requestId: String, result: CameraResult) = act {
        notice = importer.import(shotId, requestId, result)
    }
    fun saveUrl(value: String) {
        try { endpoint.baseUrl = value; notice = "API address saved" }
        catch (e: Exception) { notice = e.message ?: "Invalid API address" }
    }
    fun checkHealth() = act { apiStatus = "Checking server…"; apiStatus = endpoint.health() }
    fun signIn(email: String, password: String) {
        if (signingIn || email.isBlank() || password.isBlank()) return
        signingIn = true
        viewModelScope.launch {
            try {
                session = auth.signIn(email, password).also { cloud.sessions.save(it) }
                StudioSync.schedule(appContext)
                StudioSync.requestSoon(appContext)
                notice = "Signed in. Cloud sync runs in the background; conflicts always wait for you."
            } catch (e: Exception) { notice = e.message ?: "Sign-in failed" }
            finally { signingIn = false }
        }
    }
    fun signOut() {
        cloud.sessions.clear()
        StudioSync.cancel(appContext)
        session = null
        chat = emptyList()
        lastCost = null
        conflicts = emptyList()
        conversationId = null
        notice = "Signed out on this device. Your local data stays here."
    }
    fun clearChat() {
        chat = emptyList()
        lastCost = null
        conversationId = null
    }

    fun syncNow(choices: Map<String, app.filmmaking.studio.sync.Resolution> = emptyMap()) {
        if (syncing) return
        syncing = true
        viewModelScope.launch {
            try {
                val report = cloud.run(choices)
                conflicts = report.conflicts
                syncSummary = report.summary
                notice = "Cloud sync: " + report.summary + if (report.errors.isNotEmpty()) " — " + report.errors.first() else ""
            } catch (e: Exception) {
                notice = e.message ?: "Sync failed"
            } finally { syncing = false }
        }
    }

    fun importExternalFootage(projectId: String, uris: List<Uri>) {
        if (importing || uris.isEmpty()) return
        importing = true
        viewModelScope.launch {
            try { notice = mediaImport.import(projectId, uris).message }
            catch (e: Exception) { notice = e.message ?: "Import failed" }
            finally { importing = false }
        }
    }

    /** Runs an AI op; [accept] stores the result as pending proposals only. */
    fun aiCreate(projectId: String, op: String, instructions: String, targets: org.json.JSONArray,
                 sources: org.json.JSONArray, extra: JSONObject = JSONObject(), count: Int = 4,
                 accept: suspend (JSONObject) -> String) {
        if (aiBusy) return
        aiBusy = true
        viewModelScope.launch {
            try {
                val t = token() ?: error("Sign in under Settings to use AI")
                val result = creative.create(t, projectId, op, instructions, targets, sources, count, extra)
                notice = accept(result)
            } catch (e: Exception) { notice = e.message ?: "AI request failed" }
            finally { aiBusy = false }
        }
    }

    private val archive by lazy { ArchiveClient(appContext, endpoint) }
    fun uploadToArchive(uri: Uri, projectId: String?) {
        if (aiBusy) return
        aiBusy = true
        viewModelScope.launch {
            try {
                token() ?: error("Sign in to use your Creative Archive")
                val s = cloud.sessions.load() ?: error("Sign in to use your Creative Archive")
                val r = archive.upload(s, uri, projectId)
                notice = r.optString("title") + ": " + r.optString("status", "uploaded") +
                    (if (r.has("chunks")) " (" + r.optInt("chunks") + " passages)" else "") +
                    if (r.optString("status") == "needs_ocr") " — scanned PDF, text could not be extracted" else ""
            } catch (e: Exception) { notice = e.message ?: "Archive upload failed" }
            finally { aiBusy = false }
        }
    }

    fun searchArchive(query: String) = act {
        val t = token() ?: error("Sign in to search your Creative Archive")
        val r = creative.searchArchive(t, query)
        archiveResults = (0 until r.length()).map { r.getJSONObject(it) }
        notice = archiveResults.size.toString() + " archive passage(s) found"
    }

    var recordingIdeaId by mutableStateOf<String?>(null)
        private set
    private var recorder: android.media.MediaRecorder? = null
    private var recordingFile: java.io.File? = null

    /** Voice notes are recorded into app-private storage and attached to the idea. */
    fun toggleVoiceNote(ideaId: String) {
        val active = recorder
        if (active != null) {
            runCatching { active.stop() }; active.release(); recorder = null
            val file = recordingFile; val id = recordingIdeaId; recordingIdeaId = null
            if (file != null && id != null && file.length() > 0) act {
                features.attachToIdea(id, "voice", android.net.Uri.fromFile(file).toString(), "Voice note")
                notice = "Voice note attached (kept on this device)"
            }
            return
        }
        if (androidx.core.content.ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED) { notice = "Allow microphone access (open the camera once) to record voice notes"; return }
        val dir = java.io.File(appContext.filesDir, "voice").apply { mkdirs() }
        val file = java.io.File(dir, UUID.randomUUID().toString() + ".m4a")
        try {
            val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(appContext) else @Suppress("DEPRECATION") android.media.MediaRecorder()
            r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
            r.setOutputFile(file.absolutePath)
            r.prepare(); r.start()
            recorder = r; recordingFile = file; recordingIdeaId = ideaId
            notice = "Recording voice note… tap Stop when done"
        } catch (e: Exception) { notice = "Could not start the microphone" }
    }

    private suspend fun downscaledJpeg(resolver: ContentResolver, uri: Uri, max: Int = 960): ByteArray = withContext(Dispatchers.IO) {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= max) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Cannot read image")
        val scale = max.toFloat() / maxOf(raw.width, raw.height)
        val bmp = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(raw, (raw.width * scale).toInt(), (raw.height * scale).toInt(), true) else raw
        java.io.ByteArrayOutputStream().also { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, it) }.toByteArray()
    }

    fun addStoryboardFromImage(resolver: ContentResolver, uri: Uri, shotId: String) = act {
        val bytes = downscaledJpeg(resolver, uri)
        val dataUri = "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val projectId = data(requireNotNull(shots.value.find { it.id == shotId })).getString("projectId")
        features.addStoryboard(projectId, shotId, dataUri, "upload", JSONObject(), conceptual = false)
        notice = "Storyboard frame added (downscaled to 960 px)"
    }

    fun attachIdeaImage(resolver: ContentResolver, uri: Uri, ideaId: String) = act {
        val bytes = downscaledJpeg(resolver, uri)
        val dir = java.io.File(appContext.filesDir, "idea-images").apply { mkdirs() }
        val file = java.io.File(dir, UUID.randomUUID().toString() + ".jpg").apply { writeBytes(bytes) }
        features.attachToIdea(ideaId, "image", android.net.Uri.fromFile(file).toString(), "Image")
        notice = "Image attached to the idea (kept on this device)"
    }

    fun probeCamera() = act {
        val profile = withContext(Dispatchers.Default) { app.filmmaking.studio.camera.CameraProfileProbe.probe(appContext) }
        features.saveCameraProfile(profile, deviceReported = true)
        notice = "Camera profile read from this phone (" + profile.optJSONArray("lenses")?.length() + " lens(es))"
    }

    fun exportFile(projectId: String, format: String, onReady: (ByteArray, String, String) -> Unit) = act {
        val pkg = exporter.productionPackage(projectId)
        val base = "studio-production-" + projectId.take(8)
        when (format) {
            "json" -> onReady(pkg.toString(2).toByteArray(), "application/json", "$base.json")
            "md" -> onReady(ProductionPackage.markdown(pkg).toByteArray(), "text/markdown", "$base.md")
            "csv" -> {
                val out = java.io.ByteArrayOutputStream()
                java.util.zip.ZipOutputStream(out).use { z ->
                    ProductionPackage.csv(pkg).forEach { (n, c) -> z.putNextEntry(java.util.zip.ZipEntry(n)); z.write(c.toByteArray()); z.closeEntry() }
                }
                onReady(out.toByteArray(), "application/zip", "$base-csv.zip")
            }
            else -> {
                val images = storyboardImages(pkg)
                onReady(ProductionPackage.zip(pkg, images), "application/zip", "$base.zip")
            }
        }
        notice = "Export ready to save (metadata only, never footage)"
    }

    private fun storyboardImages(pkg: JSONObject): Map<String, ByteArray> {
        val out = mutableMapOf<String, ByteArray>()
        val index = pkg.optJSONArray("storyboardIndex") ?: return out
        val frames = workflowItems.value.filter { it.collection == "storyboards" }.associateBy { it.id }
        for (i in 0 until index.length()) {
            val f = index.getJSONObject(i); val file = f.optString("file").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val uri = frames[f.optString("id")]?.let { JSONObject(it.payload).optString("imageUri") } ?: continue
            runCatching { out[file] = android.util.Base64.decode(uri.substringAfter(","), android.util.Base64.DEFAULT) }
        }
        return out
    }
    fun ask(message: String, provider: String, project: StudioRecord?, archive: Boolean = false,
            mode: String = "Explore", role: String = "Creative Partner") {
        if (session == null) { notice = "Sign in before using AI"; return }
        if (sending || message.isBlank()) return
        val model = if (provider == "anthropic") "claude-haiku-4-5" else "gpt-5-mini"
        sending = true
        viewModelScope.launch {
            try {
                val t = token() ?: error("Sign in before using AI")
                val conv = conversationId ?: features.newConversation(project?.id, if (provider == "anthropic") "Claude" else "OpenAI", mode, role).id
                    .also { conversationId = it }
                val history = workflowItems.value.filter { it.collection == "aiMessages" && data(it).optString("conversationId") == conv }
                    .sortedBy { data(it).optString("at") }.map { AiTurn(data(it).optString("from"), data(it).optString("text")) }
                // The question is saved before the call, so nothing typed is lost if the network drops.
                features.addMessage(conv, "user", message.trim())
                val reply = ai.ask(t, conv, provider, model, message.trim(), history, project, archive, mode, role)
                features.addMessage(conv, "assistant", reply.text, reply.citations)
                lastCost = reply.costUsd
                notice = "AI response saved in this conversation"
            } catch (e: Exception) {
                notice = e.message ?: "AI request failed"
            } finally { sending = false }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = StudioRepository(StudioDatabase.get(this))
        val endpoint = ApiEndpoint(this)
        val auth = StudioAuthClient()
        val ai = StudioAiClient(endpoint)
        val importer = StudioCaptureImporter(this, StudioDatabase.get(this))
        val workflow = ProjectWorkflowRepository(StudioDatabase.get(this))
        val exporter = StudioExport(StudioDatabase.get(this))
        val creative = StudioCreativeClient(endpoint)
        val verifier = MediaVerification(this, StudioDatabase.get(this))
        val externalCamera = ExternalCameraLog(StudioDatabase.get(this))
        val features = FeatureRepository(StudioDatabase.get(this))
        val mediaImport = ExternalMediaImport(this, StudioDatabase.get(this))
        val cloud = app.filmmaking.studio.sync.StudioSync(this)
        setContent {
            val vm: StudioViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StudioViewModel(repo, endpoint, auth, ai, importer, workflow, exporter, creative, verifier, externalCamera,
                        features, mediaImport, cloud, applicationContext) as T
            })
            MaterialTheme { StudioHome(vm) }
        }
    }
}

internal fun data(record: StudioRecord): JSONObject = JSONObject(record.payload)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StudioHome(vm: StudioViewModel) {
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<String?>(null) }
    val createJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val payload = pendingExport
        pendingExport = null
        if (uri != null && payload != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
                    it.write(payload)
                } ?: error("Cannot write selected document")
            }.onSuccess { vm.report("JSON saved outside the app") }
                .onFailure { vm.report("Failed to save JSON: " + it.message) }
        }
    }
    val openBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) vm.restore(uri, context.contentResolver)
    }
    // Exports: bytes are written only to a document the user picks.
    var pendingFile by remember { mutableStateOf<ByteArray?>(null) }
    fun writeTo(uri: Uri?) {
        val bytes = pendingFile; pendingFile = null
        if (uri == null || bytes == null) return
        runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Cannot write") }
            .onSuccess { vm.report("Export saved outside the app") }.onFailure { vm.report("Failed to save export: " + it.message) }
    }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { writeTo(it) }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { writeTo(it) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { writeTo(it) }
    var importProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    val pickFootage = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val pid = importProjectId; importProjectId = null
        if (pid != null && uris.isNotEmpty()) vm.importExternalFootage(pid, uris)
    }
    var storyboardShot by rememberSaveable { mutableStateOf<String?>(null) }
    val pickStoryboard = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val shot = storyboardShot; storyboardShot = null
        if (uri != null && shot != null) vm.addStoryboardFromImage(context.contentResolver, uri, shot)
    }
    var imageIdea by rememberSaveable { mutableStateOf<String?>(null) }
    val pickIdeaImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val idea = imageIdea; imageIdea = null
        if (uri != null && idea != null) vm.attachIdeaImage(context.contentResolver, uri, idea)
    }
    var shootMode by rememberSaveable { mutableStateOf(false) }
    // Hoisted so leaving Shoot Mode (or a shot) returns to the same workspace tab.
    var workspaceSection by rememberSaveable { mutableStateOf("Develop") }
    val pickArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.uploadToArchive(uri, null)
    }
    val ideas by vm.ideas.collectAsState()
    val projects by vm.projects.collectAsState()
    val scenes by vm.scenes.collectAsState()
    val shots by vm.shots.collectAsState()
    val takes by vm.takes.collectAsState()
    val workItems by vm.workflowItems.collectAsState()
    var tab by rememberSaveable { mutableStateOf("Home") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var shotId by rememberSaveable { mutableStateOf<String?>(null) }
    var workspaceOpen by rememberSaveable { mutableStateOf(false) }
    var pendingRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingShotId by rememberSaveable { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(DuoportCaptureContract()) { result ->
        val req = pendingRequestId
        val shot = pendingShotId
        pendingRequestId = null
        pendingShotId = null
        if (result == null) vm.report("Camera cancelled: no footage was logged.")
        else if (req == null || shot == null) vm.report("Unmatched camera response: nothing imported.")
        else vm.importRecording(shot, req, result)
    }
    fun recordShot(currentProject: StudioRecord, currentShot: StudioRecord) {
        val p = data(currentShot)
        val reqId = UUID.randomUUID().toString()
        val shotSize = p.optJSONObject("camera")?.optString("shotSize") ?: "SHOT"
        val allowed = setOf("EST","EWS","WS","FS","MWS","MS","MCU","CU","ECU","OTS","POV","2S","INS","LOW","HIGH","TOP","TRK","SHOT")
        val request = OpenCameraRequest(
            requestId = reqId, mode = OpenMode.SHOT,
            shot = ShotContext(
                projectId = currentProject.id, projectName = currentProject.title,
                sceneId = p.optString("sceneId"), shotId = currentShot.id, shotCode = p.optString("code"),
                shotType = if (shotSize in allowed) shotSize else "SHOT", title = currentShot.title,
                instructions = p.optString("description"),
                takeNumber = takes.count { data(it).optString("shotId") == currentShot.id } + 1
            ),
            teleprompter = p.optString("scriptPassage").takeIf { it.isNotBlank() }?.let { Teleprompter(it) }
        )
        pendingRequestId = reqId
        pendingShotId = currentShot.id
        try { launcher.launch(request) }
        catch (error: ActivityNotFoundException) {
            pendingRequestId = null; pendingShotId = null
            vm.report("Install Duoport build with StudioCaptureActivity to film this shot.")
        } catch (error: SecurityException) {
            pendingRequestId = null; pendingShotId = null
            vm.report("Duoport rejected the capture request.")
        }
    }
    val currentProject = projects.find { it.id == projectId }
    val currentShot = shots.find { it.id == shotId }
    val title = currentShot?.title ?: currentProject?.title ?: "Filmmaking Studio"

    Scaffold(
        topBar = { TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (currentProject != null || currentShot != null) {
                    TextButton(onClick = {
                        if (shootMode) shootMode = false
                        else if (shotId != null) shotId = null
                        else if (workspaceOpen) workspaceOpen = false
                        else projectId = null
                    }) { Text("Back") }
                }
            }
        ) },
        bottomBar = { NavigationBar {
            listOf("Home", "Ideas", "Projects", "AI", "Settings").forEach { item ->
                NavigationBarItem(
                    selected = tab == item,
                    onClick = {
                        StudioDiagnostics.breadcrumb("studio", "tab_" + item.lowercase())
                        tab = item; projectId = null; shotId = null; workspaceOpen = false; shootMode = false; workspaceSection = "Develop"
                    },
                    icon = { Text(when (item) {
                        "Home" -> "⌂"; "Ideas" -> "✦"; "Projects" -> "▣"; "AI" -> "✧"; else -> "⚙"
                    }) },
                    label = { Text(item) }
                )
            }
        } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.notice.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                Text(vm.notice, Modifier.fillMaxWidth().padding(10.dp), style = MaterialTheme.typography.bodySmall)
            }
            when {
                currentProject != null && currentShot != null -> ShotScreen(
                    currentShot,
                    takes.filter { data(it).optString("shotId") == currentShot.id },
                    workItems.filter {
                        it.collection == ExternalCameraNaming.COLLECTION &&
                            data(it).optString("projectId") == currentProject.id
                    },
                    vm::changeShot,
                    vm::saveShotPlan,
                    vm::rateTake,
                    vm::preferTake,
                    { filename -> vm.logExternalCameraFilename(currentShot.id, filename) },
                    vm::removeExternalCameraReference,
                    vm
                ) { recordShot(currentProject, currentShot) }
                currentProject != null && shootMode -> ShootModeScreen(
                    vm, currentProject, shots.filter { data(it).optString("projectId") == currentProject.id }, workItems,
                    { s -> recordShot(currentProject, s) }, { shootMode = false }
                )
                currentProject != null && workspaceOpen -> ProjectWorkflowScreen(
                    vm, currentProject,
                    scenes.filter { data(it).optString("projectId") == currentProject.id }.sortedBy { data(it).optInt("order") },
                    shots.filter { data(it).optString("projectId") == currentProject.id },
                    workItems,
                    onExport = { format ->
                        vm.exportFile(currentProject.id, format) { bytes, mime, name ->
                            pendingFile = bytes
                            when (mime) { "application/json" -> saveJson.launch(name); "text/markdown" -> saveMd.launch(name); else -> saveZip.launch(name) }
                        }
                    },
                    onImportFootage = { importProjectId = currentProject.id; pickFootage.launch(arrayOf("video/*", "audio/*", "image/*", "application/octet-stream")) },
                    onPickStoryboard = { id -> storyboardShot = id; pickStoryboard.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onShootMode = { shootMode = true },
                    section = workspaceSection,
                    onSection = { workspaceSection = it }
                )
                currentProject != null -> ProjectScreen(
                    currentProject, scenes.filter { data(it).optString("projectId") == currentProject.id },
                    shots.filter { data(it).optString("projectId") == currentProject.id },
                    { vm.addScene(currentProject.id, it) }, vm::addShot, { shotId = it }, { workspaceOpen = true }
                )
                tab == "Home" -> HomeScreen(ideas.size, projects.size, shots.size, { tab = it }, { context.startActivity(Intent(context, DuoportMainActivity::class.java)) })
                tab == "Ideas" -> IdeasScreen(ideas, vm::captureIdea, vm::convertIdea) { idea ->
                    IdeaExtras(vm, idea, {
                        imageIdea = idea.id
                        pickIdeaImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, vm.recordingIdeaId == idea.id) { vm.toggleVoiceNote(idea.id) }
                }
                tab == "Projects" -> ProjectsScreen(projects, vm::createProject, { projectId = it })
                tab == "AI" -> AiDirectorScreen(vm, projects, workItems) {
                    pickArchive.launch(arrayOf("application/pdf", "text/plain", "text/markdown",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                }
                else -> SettingsScreen(vm, workItems.firstOrNull { it.collection == "cameraProfiles" },
                    {
                        vm.backup { body ->
                            pendingExport = body
                            createJson.launch("studio-backup.json")
                        }
                    },
                    { openBackup.launch(arrayOf("application/json", "text/plain")) }
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(ideas: Int, projects: Int, shots: Int, navigate: (String) -> Unit, openCamera: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("From thought to shot", style = MaterialTheme.typography.headlineMedium)
        Text("Your creative pre-production workspace. Ideas and shot plans are stored offline.")
        Text(ideas.toString() + " ideas · " + projects + " films · " + shots + " planned shots")
        Button(onClick = { navigate("Ideas") }) { Text("Capture an idea") }
        OutlinedButton(onClick = { navigate("Projects") }) { Text("Plan a film") }
        OutlinedButton(onClick = { navigate("AI") }) { Text("Open AI Director") }
        Button(onClick = openCamera, modifier = Modifier.fillMaxWidth()) { Text("Open Duoport camera") }
        Text("Duoport recording is included in this APK. Film planned shots or open the camera directly.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun IdeasScreen(ideas: List<StudioRecord>, capture: (String) -> Unit, convert: (String) -> Unit,
                        extras: @Composable (StudioRecord) -> Unit = {}) {
    var entry by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = entry, onValueChange = { entry = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Capture an idea, in your own words") }, minLines = 3)
        Button(enabled = entry.isNotBlank(), onClick = { capture(entry); entry = "" }) { Text("Save offline") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(ideas, key = { it.id }) { item ->
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(data(item).optString("original"), style = MaterialTheme.typography.bodyMedium)
                        if (data(item).optString("status") == "Converted") Text("Converted to film")
                        else TextButton(onClick = { convert(item.id) }) { Text("Develop as film") }
                        extras(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectsScreen(projects: List<StudioRecord>, create: (String) -> Unit, open: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("New film project name") })
        Button(enabled = name.isNotBlank(), onClick = { create(name); name = "" }) { Text("Create film") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(projects, key = { it.id }) { project ->
                OutlinedCard(onClick = { open(project.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(project.title, style = MaterialTheme.typography.titleMedium)
                        Text(data(project).optString("status"), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectScreen(
    project: StudioRecord,
    scenes: List<StudioRecord>,
    shots: List<StudioRecord>,
    addScene: (String) -> Unit,
    addShot: (String, String) -> Unit,
    openShot: (String) -> Unit,
    openWorkspace: () -> Unit
) {
    var sceneName by rememberSaveable(project.id) { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Scenes and shot list", style = MaterialTheme.typography.titleMedium)
        Button(onClick = openWorkspace) { Text("Open Develop · Story · Visual · Planner · Shoot · Wrap") }
        Text(scenes.size.toString() + " scenes · " + shots.size + " shots")
        OutlinedTextField(sceneName, { sceneName = it }, Modifier.fillMaxWidth(), label = { Text("Scene name") })
        Button(enabled = sceneName.isNotBlank(), onClick = { addScene(sceneName); sceneName = "" }) { Text("Add scene") }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(scenes.sortedBy { data(it).optInt("order") }, key = { it.id }) { scene ->
                var shotName by rememberSaveable(scene.id) { mutableStateOf("") }
                Card {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Scene " + data(scene).optInt("number") + " · " + scene.title,
                            style = MaterialTheme.typography.titleMedium)
                        shots.filter { data(it).optString("sceneId") == scene.id }.forEach { shot ->
                            OutlinedCard(onClick = { openShot(shot.id) }, modifier = Modifier.fillMaxWidth()) {
                                Text(data(shot).optString("code") + " — " + shot.title +
                                    " (" + data(shot).optString("status") + ")",
                                    Modifier.padding(12.dp))
                            }
                        }
                        OutlinedTextField(shotName, { shotName = it }, Modifier.fillMaxWidth(),
                            label = { Text("Shot title") })
                        TextButton(enabled = shotName.isNotBlank(), onClick = {
                            addShot(scene.id, shotName)
                            shotName = ""
                        }) { Text("Add shot") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShotScreen(
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
    val externalForShot = externalProjectFiles.filter {
        data(it).optString("shotId") == shot.id
    }.sortedBy { it.updatedAt }
    var enterWholeName by rememberSaveable(shot.id) { mutableStateOf(sequence == null) }
    var filenameInput by rememberSaveable(shot.id) { mutableStateOf("") }
    var numberInput by rememberSaveable(shot.id) { mutableStateOf("") }
    val nextName = sequence?.let { parsed ->
        runCatching {
            parsed.filenameFor(numberInput.ifBlank { parsed.nextSuffix.orEmpty() })
        }.getOrNull()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(plan.optString("code"), style = MaterialTheme.typography.headlineSmall)
        Text(shot.title, style = MaterialTheme.typography.titleMedium)
        Text("Planned status: " + plan.optString("status"))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("planned", "ready", "in progress", "skipped").forEach { status ->
                FilterChip(
                    selected = plan.optString("status") == status,
                    onClick = { changeStatus(shot.id, status) },
                    label = { Text(status) }
                )
            }
        }
        OutlinedButton(onClick = { editing = !editing }) {
            Text(if (editing) "Close shot editor" else "Edit camera · lighting · sound · script")
        }
        if (editing) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                @Composable
                fun field(label: String, value: String, change: (String) -> Unit, lines: Int = 1) {
                    OutlinedTextField(
                        value = value, onValueChange = change, modifier = Modifier.fillMaxWidth(),
                        label = { Text(label) }, minLines = lines
                    )
                }
                field("Shot title", name, { name = it })
                field("Action / composition", description, { description = it }, 3)
                field("Voiceover / teleprompter", voiceover, { voiceover = it }, 3)
                field("Shot size", shotSize, { shotSize = it })
                field("Camera angle", angle, { angle = it })
                field("Lens", lens, { lens = it })
                field("Frame rate", fps, { fps = it })
                field("Lighting notes", lightNotes, { lightNotes = it }, 2)
                field("Sound notes", soundNotes, { soundNotes = it }, 2)
                field("Editor instructions", editNotes, { editNotes = it }, 2)
                Button(
                    enabled = name.isNotBlank(),
                    onClick = {
                        savePlan(shot.id, name, description, voiceover, shotSize, angle, lens, fps,
                            lightNotes, soundNotes, editNotes)
                        editing = false
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Save planned shot offline") }
            }
        } else {
            Text("Camera plan: " + camera.optString("shotSize", "MS") +
                " · " + camera.optString("angle") +
                " · " + camera.optString("frameRate") + " fps")
        }
        Button(onClick = onShoot, modifier = Modifier.fillMaxWidth()) {
            Text("Record this shot in Duoport")
        }
        Text("Wait for Duoport's export to finish before returning. Actual footage is marked reported, never checksum-verified until checked.",
            style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text("External camera takes", style = MaterialTheme.typography.titleMedium)
        Text("Filming on DJI, Sony or another camera? Log the actual file name printed by " +
            "that camera. The app remembers the naming pattern across scenes and shots. " +
            "These are references only — footage remains on the camera/card until imported.",
            style = MaterialTheme.typography.bodySmall)
        if (!enterWholeName && sequence != null) {
            Text(
                "Detected pattern: " + sequence.prefix + "[number]" + sequence.extension,
                style = MaterialTheme.typography.labelMedium
            )
            OutlinedTextField(
                value = numberInput,
                onValueChange = { numberInput = it.filter(Char::isDigit).take(12) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("File number only (suffix)") },
                placeholder = { Text(sequence.nextSuffix ?: "0001") },
                singleLine = true
            )
            if (nextName != null) Text(
                "Filename: " + nextName, style = MaterialTheme.typography.bodyMedium
            )
            Button(
                enabled = nextName != null,
                onClick = {
                    if (nextName != null) {
                        addExternalFile(nextName)
                        numberInput = ""
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Log external camera take") }
            TextButton(onClick = { enterWholeName = true; filenameInput = "" }) {
                Text("Different camera or naming pattern")
            }
        } else {
            OutlinedTextField(
                value = filenameInput,
                onValueChange = { filenameInput = it.take(160) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Camera filename (first entry)") },
                placeholder = { Text("DJI_0045.MP4") },
                singleLine = true
            )
            Button(
                enabled = filenameInput.trim().isNotBlank(),
                onClick = {
                    addExternalFile(filenameInput)
                    filenameInput = ""
                    enterWholeName = false
                    numberInput = ""
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Log external camera take") }
            if (sequence != null) TextButton(onClick = { enterWholeName = false }) {
                Text("Use detected camera sequence")
            }
        }
        Text(
            "External references on this shot: " + externalForShot.size,
            style = MaterialTheme.typography.titleSmall
        )
        externalForShot.forEach { reference ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(reference.title, style = MaterialTheme.typography.titleSmall)
                        Text("Reference only · footage not yet imported",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { removeExternalFile(reference.id) }) {
                        Text("Remove")
                    }
                }
            }
        }
        HorizontalDivider()
        Text("Recorded takes: " + takes.size, style = MaterialTheme.typography.titleMedium)
        if (takes.isEmpty()) Text("No takes have been imported yet")
        takes.sortedBy { data(it).optInt("number") }.forEach { take ->
            val t = data(take)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Take " + t.optInt("number") + if (t.optBoolean("preferred")) " · Preferred" else "",
                        style = MaterialTheme.typography.titleMedium)
                    Text(t.optString("capturedAt"), style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("review", "usable", "unusable").forEach { quality ->
                            FilterChip(
                                selected = t.optString("quality") == quality,
                                onClick = { rateTake(take.id, quality) },
                                label = { Text(quality) }
                            )
                        }
                    }
                    if (!t.optBoolean("preferred"))
                        TextButton(onClick = { preferTake(take.id) }) { Text("Choose as preferred take") }
                    t.optJSONObject("settings")?.takeIf { it.length() > 0 }?.let { st ->
                        Text("Actual: " + listOf("frameRate", "resolution", "codec", "lens").mapNotNull { k -> st.optString(k).takeIf { it.isNotBlank() } }
                            .joinToString(" · ") + " (" + st.optString("source") + ")", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        HorizontalDivider()
        TakeExtras(vm, shot, takes.sortedBy { data(it).optInt("number") })
    }
}

@Composable
private fun SettingsScreen(vm: StudioViewModel, cameraProfile: StudioRecord?, onBackup: () -> Unit, onRestore: () -> Unit) {
    val crashContext = LocalContext.current
    var crashConsent by remember { mutableStateOf(StudioDiagnostics.isEnabled(crashContext)) }
    var url by rememberSaveable { mutableStateOf(vm.apiUrl) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI backend", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("AWS Lambda HTTPS Function URL") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveUrl(url) }) { Text("Save URL") }
            OutlinedButton(onClick = { vm.checkHealth() }) { Text("Test connection") }
        }
        if (vm.apiStatus.isNotBlank()) Text(vm.apiStatus)
        HorizontalDivider()
        Text("Your Studio account", style = MaterialTheme.typography.titleLarge)
        val session = vm.session
        if (session == null) {
            Text("Use the email/password account from the existing Lovable-linked Supabase project.")
            OutlinedTextField(value = email, onValueChange = { email = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Email") }, singleLine = true)
            OutlinedTextField(value = password, onValueChange = { password = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(),
                singleLine = true)
            Button(enabled = !vm.signingIn && email.isNotBlank() && password.isNotBlank(),
                onClick = { vm.signIn(email, password); password = "" }) {
                Text(if (vm.signingIn) "Signing in…" else "Sign in")
            }
        } else {
            Text("Signed in as " + session.email)
            Text("You stay signed in on this device; the session renews itself and is never included in backups.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out") }
        }
        HorizontalDivider()
        CloudSyncSection(vm)
        HorizontalDivider()
        CameraProfileSection(vm, cameraProfile)
        HorizontalDivider()
        Text("Crash diagnostics", style = MaterialTheme.typography.titleLarge)
        Text("Share anonymous crash reports and ANR diagnostics with the Studio development team. " +
            "Reports may contain technical stack traces and app/device details; no scripts, " +
            "AI prompts, camera frames or account identifiers are deliberately attached.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Share crash reports", modifier = Modifier.weight(1f))
            Switch(
                checked = crashConsent && StudioDiagnostics.isConfigured(),
                enabled = StudioDiagnostics.isConfigured(),
                onCheckedChange = { on ->
                    val enabled = StudioDiagnostics.setConsent(crashContext, on)
                    crashConsent = on && enabled
                    vm.report(if (crashConsent) "Crash reporting enabled" else "Crash reporting disabled")
                }
            )
        }
        if (!StudioDiagnostics.isConfigured()) {
            Text("Crash reporting is not connected yet. The developer must configure " +
                "STUDIO_SENTRY_DSN for this APK build.",
                style = MaterialTheme.typography.bodySmall)
        } else if (crashConsent) {
            OutlinedButton(onClick = {
                val queued = StudioDiagnostics.sendTestEvent()
                vm.report(if (queued) "Diagnostic test event queued for Sentry" else "Crash reports are disabled")
            }) { Text("Send safe diagnostic test event") }
        }
        HorizontalDivider()
        Text("Offline local storage", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onBackup) { Text("Export offline backup JSON") }
        OutlinedButton(onClick = onRestore) { Text("Import backup (refuses conflicting records)") }
        Text("Everything is saved on this device first. Cloud sync (when signed in) copies it to your Studio account; footage itself never leaves the phone.",
            style = MaterialTheme.typography.bodySmall)
    }
}
