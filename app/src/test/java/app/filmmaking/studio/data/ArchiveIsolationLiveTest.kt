package app.filmmaking.studio.data

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Live Creative Archive privacy check through the native client code, mirroring the web acceptance test #15:
 * a second account cannot see, list, download, plant, index, search or analyse the first account's document.
 *
 * Skipped unless two EXISTING test accounts are supplied (never commit these):
 *   STUDIO_LIVE_A_EMAIL / STUDIO_LIVE_A_PASSWORD / STUDIO_LIVE_B_EMAIL / STUDIO_LIVE_B_PASSWORD
 * Optional STUDIO_LIVE_API (the HTTPS AI backend) adds the ingest / search / DNA checks (a few cents of embeddings).
 * Run: ./gradlew --no-daemon :app:testDebugUnitTest --tests '*ArchiveIsolationLiveTest*'
 * The uploaded test document is purged at the end.
 */
class ArchiveIsolationLiveTest {
    private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()
    private val rest = ArchiveRest(http)
    private val json = "application/json".toMediaType()

    private fun api(base: String, token: String, path: String, body: JSONObject): Pair<Int, String> =
        rest.call(Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer $token").post(body.toString().toRequestBody(json)).build())

    @Test fun secondAccountCannotReachFirstAccountsArchive() = runBlocking {
        val creds = listOf("STUDIO_LIVE_A_EMAIL", "STUDIO_LIVE_A_PASSWORD", "STUDIO_LIVE_B_EMAIL", "STUDIO_LIVE_B_PASSWORD").map(::env)
        assumeTrue("Live archive test needs two test accounts in the environment", creds.all { it != null })
        val auth = StudioAuthClient()
        val a = auth.signIn(creds[0]!!, creds[1]!!)
        val b = auth.signIn(creds[2]!!, creds[3]!!)
        assertTrue("Use two different accounts", a.userId.isNotBlank() && a.userId != b.userId)
        val apiBase = env("STUDIO_LIVE_API")
        val text = "# Lighthouse notes\n\nThe keeper measures time by the honest clock of the tide. Isolation marker ${System.nanoTime()}.\n"
        val doc = rest.upload(a, "isolation-test.md", "text/markdown", text.toByteArray(), null, apiBase)
        val id = doc.getString("documentId"); val path = doc.getString("storagePath")
        try {
            // Owner sees it; the other account sees nothing through RLS.
            assertEquals(1, JSONArray(rest.call(rest.authed(a, "/rest/v1/archive_documents?select=id&id=eq.$id").get().build()).second).length())
            assertEquals("[]", rest.call(rest.authed(b, "/rest/v1/archive_documents?select=id&id=eq.$id").get().build()).second.trim())
            assertEquals("[]", rest.call(rest.authed(b, "/rest/v1/archive_chunks?select=id&document_id=eq.$id").get().build()).second.trim())
            assertFalse(rest.analysable(b).any { it.optString("id") == id })
            // Storage: B cannot download A's original.
            val (dl, _) = rest.call(rest.authed(b, "/storage/v1/object/authenticated/studio-files/$path").get().build())
            assertTrue("download should be refused, got $dl", dl !in 200..299)
            // B cannot plant a row pointing at A's storage, nor a chunk under A's document.
            val plant = JSONObject().put("user_id", b.userId).put("title", "x").put("source_type", "md").put("storage_path", path)
            assertTrue(rest.call(rest.authed(b, "/rest/v1/archive_documents").post(plant.toString().toRequestBody(json)).build()).first !in 200..299)
            val chunk = JSONObject().put("user_id", b.userId).put("document_id", id).put("ord", 99).put("content", "x")
            assertTrue(rest.call(rest.authed(b, "/rest/v1/archive_chunks").post(chunk.toString().toRequestBody(json)).build()).first !in 200..299)
            if (apiBase != null) {
                assertEquals(404, api(apiBase, b.accessToken, "/api/v1/archive/ingest", JSONObject().put("documentId", id)).first)
                val (sc, st) = api(apiBase, b.accessToken, "/api/v1/archive/search",
                    JSONObject().put("query", "honest clock").put("documentIds", JSONArray().put(id)).put("mode", "keyword"))
                assertEquals(200, sc); assertEquals(0, JSONObject(st).getJSONArray("results").length())
                val dna = JSONObject().put("documentIds", JSONArray().put(id)).put("provider", "openai").put("model", "gpt-5-mini")
                assertEquals(400, api(apiBase, b.accessToken, "/api/v1/archive/dna", dna).first)
            }
        } finally {
            rest.purge(a, id, path)
        }
        assertEquals("[]", rest.call(rest.authed(a, "/rest/v1/archive_documents?select=id&id=eq.$id").get().build()).second.trim())
    }
}
