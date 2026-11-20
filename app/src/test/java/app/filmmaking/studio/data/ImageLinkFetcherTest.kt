package app.filmmaking.studio.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class ImageLinkFetcherTest {
    private class Page(val type: String, val body: ByteArray, val status: Int = 200)
    private val pages = mutableMapOf<String, Page>()
    private lateinit var server: ServerSocket
    private val base get() = "http://127.0.0.1:${server.localPort}"
    private val fetcher = ImageLinkFetcher(upgradeToHttps = false, maxBytes = 64 * 1024)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)

    @Before fun start() {
        server = ServerSocket(0)
        thread(isDaemon = true) { while (!server.isClosed) runCatching { server.accept() }.getOrNull()?.let { s -> thread(isDaemon = true) { serve(s) } } }
    }
    @After fun stop() = server.close()

    private fun serve(sock: Socket) = sock.use {
        val input = it.getInputStream().bufferedReader()
        val path = input.readLine()?.split(" ")?.getOrNull(1) ?: return
        while (input.readLine()?.isNotEmpty() == true) Unit
        val p = pages[path] ?: Page("text/plain", "missing".toByteArray(), 404)
        val out = it.getOutputStream()
        out.write("HTTP/1.1 ${p.status} X\r\nContent-Type: ${p.type}\r\nContent-Length: ${p.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(p.body); out.flush()
    }

    private fun failsWith(link: String, text: String) = runBlocking {
        try { fetcher.fetch(link); fail("expected failure for $link") }
        catch (e: ImageLinkException) { assertTrue(e.message, e.message!!.contains(text)) }
    }

    @Test fun directImageLinkIsDownloaded() = runBlocking {
        pages["/a.png"] = Page("image/png", png)
        val f = fetcher.fetch("$base/a.png")
        assertArrayEquals(png, f.bytes)
        assertEquals("$base/a.png", f.pageUrl)
    }

    @Test fun webPageUsesItsPreviewImageAndTitle() = runBlocking {
        pages["/img/hero.png"] = Page("image/png", png)
        pages["/post"] = Page("text/html; charset=utf-8", """<html><head><title>Fallback</title>
            <meta content="Rain at dusk &amp; neon" property="og:title"><meta property='og:image' content='/img/hero.png'></head></html>""".toByteArray())
        val f = fetcher.fetch("$base/post")
        assertArrayEquals(png, f.bytes)
        assertEquals("$base/img/hero.png", f.imageUrl)
        assertEquals("Rain at dusk & neon", f.pageTitle)
    }

    @Test fun pageWithoutPreviewImageExplainsWhatToDo() {
        pages["/plain"] = Page("text/html", "<html><title>x</title></html>".toByteArray())
        failsWith("$base/plain", "no preview image")
    }

    @Test fun nonImagesSvgFakesAndOversizedFilesAreRejected() {
        pages["/doc.pdf"] = Page("application/pdf", "%PDF".toByteArray())
        pages["/d.svg"] = Page("image/svg+xml", "<svg/>".toByteArray())
        pages["/fake.jpg"] = Page("image/jpeg", "not really an image".toByteArray())
        pages["/big.png"] = Page("image/png", png + ByteArray(70 * 1024))
        failsWith("$base/doc.pdf", "isn't an image")
        failsWith("$base/d.svg", "SVG")
        failsWith("$base/fake.jpg", "readable image")
        failsWith("$base/big.png", "larger than")
        failsWith("$base/gone.png", "HTTP 404")
    }

    @Test fun badLinksAreRefusedBeforeAnyRequest() {
        failsWith("  ", "Paste a link")
        failsWith("ftp://example.com/a.png", "Only web links")
        failsWith("file:///sdcard/a.png", "Only web links")
    }

    @Test fun httpLinksAreUpgradedAndBareHostsGetHttps() {
        // Nothing listens on 443 locally, so both fail to connect, but only after being turned into https.
        val secure = ImageLinkFetcher(maxBytes = 1024)
        runBlocking {
            for (l in listOf("http://127.0.0.1:1/a.png", "127.0.0.1:1/a.png")) {
                try { secure.fetch(l); fail() } catch (e: ImageLinkException) { assertTrue(e.message, e.message!!.contains("Couldn't reach")) }
            }
        }
        assertTrue(ImageLinkFetcher.looksLikeImage(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
    }
}
