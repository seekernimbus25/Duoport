package app.filmmaking.studio

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.filmmaking.studio.data.StudioRecord
import app.filmmaking.studio.ui.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the main Studio screens at phone size (390×844 dp) to app/build/screenshots, to compare with the web app's
 * screens. Sample content only, mirroring the web prototype's seed data. Opt-in:
 * `./gradlew :app:testDebugUnitTest --tests '*StudioScreenshots*' -Dstudio.screenshots=true`
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class StudioScreenshots {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun optIn() = Assume.assumeTrue(System.getProperty("studio.screenshots") == "true")

    private fun rec(collection: String, id: String, title: String, body: JSONObject) =
        StudioRecord(collection, id, title, body.put("id", id).toString())

    private val tea = rec("projects", "p-tea", "The Tea Film", JSONObject().put("name", "The Tea Film").put("format", "Short Film").put("aspectRatio", "9:16")
        .put("status", "Pre-production").put("logline", "Four minutes of steeping tea, and everything I learned about waiting.")
        .put("lastOpenedAt", "2026-11-20T10:00:00Z").put("createdAt", "2026-10-30T10:00:00Z").put("decisions", JSONArray()))
    private val crowd = rec("projects", "p-crowd", "Crowded Loneliness", JSONObject().put("name", "Crowded Loneliness").put("format", "Documentary")
        .put("aspectRatio", "16:9").put("status", "Development").put("logline", "A thousand people, and no one.")
        .put("lastOpenedAt", "2026-11-18T10:00:00Z").put("createdAt", "2026-11-15T10:00:00Z"))
    private val projects = listOf(tea, crowd)
    private val scenes = listOf(
        rec("scenes", "sc1", "The kettle", JSONObject().put("projectId", "p-tea").put("number", 1).put("order", 1)),
        rec("scenes", "sc2", "Steeping", JSONObject().put("projectId", "p-tea").put("number", 2).put("order", 2)),
    )
    private val shots = (1..6).map { n ->
        val planned = n == 1
        rec("shots", "s$n", listOf("Kettle on the hob", "Steam rising", "Hands on the cup", "Leaves unfurling", "Clock on the wall", "First sip")[n - 1],
            JSONObject().put("projectId", "p-tea").put("sceneId", if (n <= 3) "sc1" else "sc2").put("code", (if (n <= 3) "1-" else "2-") + n)
                .put("status", if (planned) "ready" else "planned").put("description", if (planned) "Locked-off, morning light" else "")
                .put("camera", JSONObject().put("shotSize", listOf("WS", "CU", "MCU", "ECU", "INS", "CU")[n - 1]).put("lens", if (planned) "35mm" else ""))
                .put("lighting", JSONObject().put("source", if (planned) "Window, soft" else "")))
    }
    private val takes = listOf(rec("takes", "t1", "Take 1", JSONObject().put("shotId", "s1").put("number", 1).put("quality", "usable")))
    private val items = listOf(
        rec("checklist", "c1", "Buy fresh tea leaves", JSONObject().put("projectId", "p-tea").put("category", "Props").put("status", "unresolved")),
        rec("checklist", "c2", "Ask stall owner for permission", JSONObject().put("projectId", "p-tea").put("category", "Location").put("status", "unresolved")),
    )
    private fun idea(id: String, title: String, kind: String, status: String, text: String, at: String) =
        rec("ideas", id, title, JSONObject().put("title", title).put("kind", kind).put("status", status).put("original", text).put("createdAt", at))
    private val ideas = listOf(
        idea("i1", "The four minutes", "observation", "Converted", "Tea takes four minutes to steep. Maybe that's the only time I'm not in a hurry.", "2026-10-30T09:00:00Z"),
        idea("i2", "Do trains remember?", "question", "Exploring", "If a train carries thousands of people a day, is it the most crowded or the loneliest place?", "2026-11-15T09:00:00Z"),
        idea("i3", "Grandmother's hands reading", "image", "Inbox", "Her hands on the book, the dust in window light. Shoot before the light goes.", "2026-11-17T09:00:00Z"),
        idea("i4", "Rain sound on tin roof", "sound", "Inbox", "Record the tin roof during the next rain. Could be the whole score.", "2026-11-12T09:00:00Z"),
    )

    private val nav = listOf(NavItem("Home", "Home", Lucide.House), NavItem("Ideas", "Ideas", Lucide.Lightbulb),
        NavItem("Projects", "Projects", Lucide.Clapperboard), NavItem("AI", "AI Studio", Lucide.Sparkles), NavItem("Library", "Library", Lucide.Library))

    private fun shot(name: String, tab: String?, content: @Composable () -> Unit) {
        compose.setContent {
            StudioTheme {
                Column(Modifier.fillMaxSize().background(StudioColors.Background)) {
                    Box(Modifier.weight(1f)) {
                        content()
                        if (tab != null && tab != "AI") AssistantFab({}, Modifier.align(Alignment.BottomEnd).padding(16.dp))
                    }
                    if (tab != null) StudioBottomNav(nav, tab) {}
                }
            }
        }
        compose.waitForIdle()
        // captureToImage() waits for a hardware frame Robolectric never produces; draw the window in software instead.
        val view = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bmp))
        val dir = File("build/screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot("home", "Home") {
        HomeScreen(ideas, projects, shots, takes, items, {}, { _, _ -> }, {}, {}, {}, {})
    }

    @Test fun ideas() = shot("ideas", "Ideas") { IdeasScreen(ideas, {}, {}) }

    @Test fun projects() = shot("projects", "Projects") { ProjectsScreen(projects, shots, takes, items, {}, {}) }

    @Test fun projectShots() = shot("project-shots", null) {
        Column {
            StudioTopBar("The Tea Film", "Pre-production · Short Film", {})
            StudioTabs(PROJECT_TABS, "Shots") {}
            ShotsTab(scenes, shots, takes, {}, { _, _ -> }, {})
        }
    }
}
