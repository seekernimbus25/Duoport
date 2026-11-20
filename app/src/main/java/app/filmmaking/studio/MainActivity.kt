package app.filmmaking.studio

import android.os.Bundle
import android.net.Uri
import android.content.ContentResolver
import androidx.activity.result.contract.ActivityResultContracts
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
    private val verifier: MediaVerification
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
    var session by mutableStateOf<StudioAuthClient.Session?>(null)
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
    private var conversationId = UUID.randomUUID().toString()
    val apiUrl get() = endpoint.baseUrl

    private fun act(block: suspend () -> Unit) {
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
        val token = session?.accessToken
        if (token.isNullOrBlank()) { notice = "Sign in to request AI proposals"; return }
        if (generating) return
        generating = true
        generatedOptions = null
        viewModelScope.launch {
            try {
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
                session = auth.signIn(email, password)
                notice = "Signed in to the existing Studio Supabase project"
            } catch (e: Exception) { notice = e.message ?: "Sign-in failed" }
            finally { signingIn = false }
        }
    }
    fun signOut() {
        session = null
        chat = emptyList()
        lastCost = null
        conversationId = UUID.randomUUID().toString()
        notice = "Signed out locally"
    }
    fun clearChat() {
        chat = emptyList()
        lastCost = null
        conversationId = UUID.randomUUID().toString()
    }
    fun ask(message: String, provider: String, project: StudioRecord?) {
        val token = session?.accessToken
        if (token == null) { notice = "Sign in before using AI"; return }
        if (sending || message.isBlank()) return
        val model = if (provider == "anthropic") "claude-haiku-4-5" else "gpt-5-mini"
        sending = true
        viewModelScope.launch {
            try {
                val reply = ai.ask(
                    token, conversationId, provider, model, message.trim(), chat, project
                )
                chat = chat + AiTurn("user", message.trim()) + AiTurn("assistant", reply.text)
                lastCost = reply.costUsd
                notice = "AI response received"
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
        setContent {
            val vm: StudioViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    StudioViewModel(repo, endpoint, auth, ai, importer, workflow, exporter, creative, verifier) as T
            })
            MaterialTheme { StudioHome(vm) }
        }
    }
}

private fun data(record: StudioRecord): JSONObject = JSONObject(record.payload)

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
    val currentProject = projects.find { it.id == projectId }
    val currentShot = shots.find { it.id == shotId }
    val title = currentShot?.title ?: currentProject?.title ?: "Filmmaking Studio"

    Scaffold(
        topBar = { TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                if (currentProject != null || currentShot != null) {
                    TextButton(onClick = {
                        if (shotId != null) shotId = null
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
                        tab = item; projectId = null; shotId = null; workspaceOpen = false
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
                    vm::changeShot,
                    vm::saveShotPlan,
                    vm::rateTake,
                    vm::preferTake
                ) {
                    val p = data(currentShot)
                    val reqId = UUID.randomUUID().toString()
                    val shotSize = p.optJSONObject("camera")?.optString("shotSize") ?: "SHOT"
                    val allowed = setOf("EST","EWS","WS","FS","MWS","MS","MCU","CU","ECU","OTS","POV","2S","INS","LOW","HIGH","TOP","TRK","SHOT")
                    val request = OpenCameraRequest(
                        requestId = reqId, mode = OpenMode.SHOT,
                        shot = ShotContext(
                            projectId = currentProject.id, projectName = currentProject.title,
                            sceneId = p.optString("sceneId"), shotId = currentShot.id,
                            shotCode = p.optString("code"),
                            shotType = if (shotSize in allowed) shotSize else "SHOT",
                            title = currentShot.title,
                            instructions = p.optString("description"),
                            takeNumber = takes.count { data(it).optString("shotId") == currentShot.id } + 1
                        ),
                        teleprompter = p.optString("scriptPassage").takeIf { it.isNotBlank() }?.let {
                            Teleprompter(it)
                        }
                    )
                    pendingRequestId = reqId
                    pendingShotId = currentShot.id
                    try { launcher.launch(request) }
                    catch (error: ActivityNotFoundException) {
                        pendingRequestId = null
                        pendingShotId = null
                        vm.report("Install Duoport build with StudioCaptureActivity to film this shot.")
                    }
                    catch (error: SecurityException) {
                        pendingRequestId = null
                        pendingShotId = null
                        vm.report("Duoport rejected the capture request.")
                    }
                }
                currentProject != null && workspaceOpen -> ProjectWorkflowScreen(
                    currentProject,
                    shots.filter { data(it).optString("projectId") == currentProject.id },
                    workItems.filter {
                        data(it).optString("projectId") == currentProject.id
                    },
                    { kind, title, detail, shot -> vm.createWorkflow(currentProject.id, kind, title, detail, shot) },
                    vm::updateWorkflow,
                    { id -> vm.approveTreatment(currentProject.id, id) },
                    vm::approveScript,
                    vm::toggleChecklist,
                    vm.session != null,
                    vm.generating,
                    vm.generatedOptions?.takeIf { it.projectId == currentProject.id },
                    { op, instructions -> vm.generateOptions(currentProject, op, instructions) },
                    vm::acceptGenerated,
                    vm::clearGenerated,
                    vm.verifying,
                    { vm.verifyRecordedMedia(currentProject.id) },
                    {
                        vm.exportProduction(currentProject.id) { body ->
                            pendingExport = body
                            createJson.launch("studio-production-" + currentProject.id.take(8) + ".json")
                        }
                    }
                )
                currentProject != null -> ProjectScreen(
                    currentProject, scenes.filter { data(it).optString("projectId") == currentProject.id },
                    shots.filter { data(it).optString("projectId") == currentProject.id },
                    { vm.addScene(currentProject.id, it) }, vm::addShot, { shotId = it }, { workspaceOpen = true }
                )
                tab == "Home" -> HomeScreen(ideas.size, projects.size, shots.size, { tab = it }, { context.startActivity(Intent(context, DuoportMainActivity::class.java)) })
                tab == "Ideas" -> IdeasScreen(ideas, vm::captureIdea, vm::convertIdea)
                tab == "Projects" -> ProjectsScreen(projects, vm::createProject, { projectId = it })
                tab == "AI" -> AiScreen(vm, projects)
                else -> SettingsScreen(vm,
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
private fun IdeasScreen(ideas: List<StudioRecord>, capture: (String) -> Unit, convert: (String) -> Unit) {
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
    changeStatus: (String, String) -> Unit,
    savePlan: (String, String, String, String, String, String, String, String, String, String, String) -> Unit,
    rateTake: (String, String) -> Unit,
    preferTake: (String) -> Unit,
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
                }
            }
        }
    }
}

@Composable
private fun AiScreen(vm: StudioViewModel, projects: List<StudioRecord>) {
    var input by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("anthropic") }
    var projectId by rememberSaveable { mutableStateOf<String?>(null) }
    var showProjects by remember { mutableStateOf(false) }
    val project = projects.find { it.id == projectId }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI Director", style = MaterialTheme.typography.titleLarge)
        if (vm.session == null) {
            Text("Sign in under Settings using your existing Studio Supabase account.")
            Text("Model API keys are securely stored on AWS, not in this APK.",
                style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = provider == "anthropic", onClick = { provider = "anthropic" },
                label = { Text("Claude Haiku") })
            FilterChip(selected = provider == "openai", onClick = { provider = "openai" },
                label = { Text("GPT-5 mini") })
        }
        Box {
            OutlinedButton(onClick = { showProjects = true }) {
                Text("Film context: " + (project?.title ?: "General conversation"))
            }
            DropdownMenu(expanded = showProjects, onDismissRequest = { showProjects = false }) {
                DropdownMenuItem(text = { Text("General conversation") }, onClick = {
                    projectId = null; showProjects = false; vm.clearChat()
                })
                projects.forEach { p ->
                    DropdownMenuItem(text = { Text(p.title) }, onClick = {
                        projectId = p.id; showProjects = false; vm.clearChat()
                    })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chat session is in memory only", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.clearChat() }) { Text("Clear") }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.chat) { turn ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(if (turn.from == "user") "You" else "AI Director",
                            style = MaterialTheme.typography.labelLarge)
                        Text(turn.text)
                    }
                }
            }
        }
        if (vm.lastCost != null) Text(
            "Last estimated provider cost: $" + String.format("%.4f", vm.lastCost),
            style = MaterialTheme.typography.labelSmall
        )
        OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask about story, shots, tone or direction") }, maxLines = 4)
        Button(
            enabled = input.isNotBlank() && !vm.sending,
            onClick = { vm.ask(input, provider, project); input = "" },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (vm.sending) "Thinking…" else "Send to AI Director") }
    }
}

@Composable
private fun SettingsScreen(vm: StudioViewModel, onBackup: () -> Unit, onRestore: () -> Unit) {
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
            Text("Access token is kept only in memory; reopening the app requires signing in again.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { vm.signOut() }) { Text("Sign out") }
        }
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
        Text("Ideas, films, scenes and shot plans are saved on this device. Cloud sync and camera recording integration are not yet available.")
    }
}
