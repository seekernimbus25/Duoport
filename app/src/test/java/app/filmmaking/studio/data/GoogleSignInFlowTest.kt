package app.filmmaking.studio.data

import app.filmmaking.studio.sync.CloudAccessCheck
import app.filmmaking.studio.sync.LinkDecision
import app.filmmaking.studio.sync.LocalOwner
import app.filmmaking.studio.sync.OwnershipGate
import app.filmmaking.studio.sync.RawRest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test sign-in flow: Credential Manager's output (ID token + raw nonce) → Supabase Auth `grant_type=id_token`
 * against a local fake of Supabase Auth, then the ownership gate and RLS check that run before any sync.
 */
class GoogleSignInFlowTest {
    private data class Hit(val method: String, val path: String, val apikey: String?, val bearer: String?, val body: String)
    private val hits = CopyOnWriteArrayList<Hit>()
    private lateinit var server: ServerSocket
    private var tokenStatus = 200
    private var tokenBody = ""
    private lateinit var auth: StudioAuthClient

    private val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ1c2VyLTEifQ.c2ln"

    @Before fun start() {
        server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val sock = runCatching { server.accept() }.getOrNull() ?: break
                sock.use { serve(it) }
            }
        }
        auth = StudioAuthClient("http://127.0.0.1:" + server.localPort, "sb_publishable_test")
    }

    /** Minimal HTTP/1.1 fake of Supabase Auth: one request per connection, then close. */
    private fun serve(sock: java.net.Socket) {
        val input = sock.getInputStream().buffered()
        fun line(): String { val b = StringBuilder(); while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) b.append(c.toChar()) }; return b.toString() }
        val (method, target) = line().split(' ').let { it[0] to it[1] }
        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = String(ByteArray(len).also { var o = 0; while (o < len) { val n = input.read(it, o, len - o); if (n < 0) break; o += n } })
        hits += Hit(method, target, headers["apikey"], headers["authorization"], body)
        val path = target.substringBefore('?')
        val (code, out) = when (path) {
            "/auth/v1/token" -> tokenStatus to tokenBody
            "/auth/v1/user" -> 200 to """{"id":"user-1","email":"director@example.com"}"""
            else -> 204 to ""
        }
        val bytes = out.toByteArray()
        sock.getOutputStream().apply {
            write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes); flush()
        }
    }

    @After fun stop() = server.close()

    @Test fun nonceGoesHashedToGoogleAndRawToSupabase() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", GoogleNonce.sha256Hex("abc"))
        val n = GoogleNonce.create()
        assertEquals(GoogleNonce.sha256Hex(n.raw), n.hashed)
        assertTrue(n.raw.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertNotEquals(n.raw, GoogleNonce.create().raw)
        val body = StudioAuthClient.idTokenBody("id.token.value", n.raw)
        assertEquals("google", body.getString("provider"))
        assertEquals(n.raw, body.getString("nonce"))
        assertEquals(setOf("provider", "id_token", "nonce"), body.keys().asSequence().toSet())
    }

    @Test fun onlyAWellFormedClientIdIsAccepted() {
        assertEquals("1234567890-abcdef0123.apps.googleusercontent.com",
            GoogleAuthConfig.webClientIdOrNull(" 1234567890-abcdef0123.apps.googleusercontent.com "))
        listOf("", "GOCSPX-thisIsAClientSecret", "abc.apps.googleusercontent.com", "123-abc.apps.googleusercontent.com.evil", null)
            .forEach { assertNull(it, GoogleAuthConfig.webClientIdOrNull(it)) }
    }

    @Test fun googleIdTokenIsExchangedWithSupabaseAuth() = runBlocking {
        tokenBody = """{"access_token":"$jwt","refresh_token":"r1","expires_in":3600,
            "user":{"id":"user-1","email":"director@example.com"}}"""
        val s = auth.signInWithGoogle("google.id.token", "raw-nonce")
        val hit = hits.single()
        assertEquals("POST", hit.method)
        assertEquals("/auth/v1/token?grant_type=id_token", hit.path)
        assertEquals("sb_publishable_test", hit.apikey)
        val sent = JSONObject(hit.body)
        assertEquals("google.id.token", sent.getString("id_token")); assertEquals("raw-nonce", sent.getString("nonce"))
        assertEquals("user-1", s.userId); assertEquals("director@example.com", s.email)
        assertEquals("google", s.provider); assertEquals("r1", s.refreshToken)
        // Session restore checks the token with the server; sign-out revokes only this device.
        assertEquals("user-1", auth.user(s.accessToken).getString("id"))
        auth.logout(s.accessToken)
        assertEquals("Bearer $jwt", hits.last().bearer)
        assertEquals("/auth/v1/logout?scope=local", hits.last().path)
        // A refresh keeps the provider label.
        assertEquals("google", auth.refresh(s).provider)
    }

    @Test fun supabaseRefusalsBecomePlainMessages() = runBlocking {
        tokenStatus = 400
        tokenBody = """{"code":400,"error_code":"validation_failed","msg":"Unacceptable audience in id_token: [123-x.apps.googleusercontent.com]"}"""
        try { auth.signInWithGoogle("t.o.k", "n"); fail("should refuse") }
        catch (e: AuthException) { assertTrue(e.message!!, "Client IDs" in e.message!!) }
        tokenBody = """{"msg":"Unsupported provider: provider is not enabled"}"""
        try { auth.signInWithGoogle("t.o.k", "n"); fail("should refuse") }
        catch (e: AuthException) { assertTrue(e.message!!, "not switched on" in e.message!!) }
    }

    @Test fun localWorkIsNeverMixedBetweenAccounts() {
        val a = LocalOwner("user-a", "a@example.com")
        assertEquals(LinkDecision.Proceed, OwnershipGate.decide(a, emptySet(), "user-a", 12))
        assertEquals(LinkDecision.OtherAccount("a@example.com"), OwnershipGate.decide(a, emptySet(), "user-b", 12))
        assertEquals(LinkDecision.LinkEmpty, OwnershipGate.decide(null, emptySet(), "user-b", 0))
        assertEquals(LinkDecision.AskToLink(7), OwnershipGate.decide(null, emptySet(), "user-b", 7))
        // Installs that synced before linking existed: the account with a sync base owns the work.
        assertEquals(LinkDecision.Proceed, OwnershipGate.decide(null, setOf("user-a"), "user-a", 5))
        assertTrue(OwnershipGate.decide(null, setOf("user-a"), "user-b", 5) is LinkDecision.OtherAccount)
    }

    private class FakeRest(val respond: (String, String) -> Pair<Int, String>) : RawRest {
        val calls = mutableListOf<String>()
        override suspend fun request(method: String, path: String, body: String?, prefer: String?): Pair<Int, String> {
            calls += "$method $path"; return respond(method, path)
        }
    }

    @Test fun rlsMustRefuseAnotherUsersRowBeforeAnythingSyncs() = runBlocking {
        val ok = FakeRest { m, _ -> if (m == "POST") 403 to """{"code":"42501","message":"new row violates row-level security policy"}""" else 200 to "[]" }
        assertNull(CloudAccessCheck.verify(ok, "user-a", listOf("projects", "takes")))
        assertTrue(ok.calls.any { it.startsWith("GET projects?select=id&user_id=neq.user-a") })

        val leaky = FakeRest { _, p -> 200 to if ("neq" in p) """[{"id":"x"}]""" else "[]" }
        assertNotNull(CloudAccessCheck.verify(leaky, "user-a", listOf("projects")))

        val open = FakeRest { m, _ -> if (m == "POST") 201 to "" else 200 to "[]" }
        assertNotNull(CloudAccessCheck.verify(open, "user-a", listOf("projects")))
        assertTrue("the accidental probe row is removed", open.calls.any { it.startsWith("DELETE ideas?id=eq.") })

        val missing = FakeRest { _, p -> if (p.startsWith("takes")) 404 to "{}" else 200 to "[]" }
        assertTrue(CloudAccessCheck.verify(missing, "user-a", listOf("projects", "takes"))!!.contains("takes"))
    }
}
