package app.filmmaking.studio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An image fetched from a pasted link, ready to downscale. [pageUrl] is what the person pasted. */
class FetchedImage(val bytes: ByteArray, val imageUrl: String, val pageUrl: String, val pageTitle: String?)

class ImageLinkException(message: String) : Exception(message)

/**
 * Fetches an inspiration image from a link, on the phone (no server, no cost). A direct image link is
 * downloaded as is; a web page link uses the page's preview image (`og:image` / `twitter:image`).
 * HTTPS only (an `http://` link is upgraded), at most [maxBytes], and the bytes must really be an image.
 */
class ImageLinkFetcher(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).build(),
    private val maxBytes: Int = 12 * 1024 * 1024,
    private val upgradeToHttps: Boolean = true, // tests use a plain local server
) {
    suspend fun fetch(link: String): FetchedImage = withContext(Dispatchers.IO) {
        val page = parse(link)
        get(page).use { res ->
            val type = res.header("Content-Type").orEmpty().lowercase()
            when {
                type.startsWith("image/") -> FetchedImage(image(res, type), res.request.url.toString(), page.toString(), null)
                type.contains("html") -> {
                    val html = String(read(res, HTML_LIMIT, allowTruncate = true), Charsets.UTF_8)
                    val found = previewImage(html) ?: throw ImageLinkException(
                        "That page has no preview image. Open the image itself (long-press it → Copy image address) and paste that link.")
                    val img = res.request.url.resolve(found)?.let { secure(it) } ?: throw ImageLinkException("The page's image link is not valid.")
                    get(img).use { r2 ->
                        val t2 = r2.header("Content-Type").orEmpty().lowercase()
                        FetchedImage(image(r2, t2), r2.request.url.toString(), page.toString(), title(html))
                    }
                }
                else -> throw ImageLinkException("That link isn't an image or a web page (it returned ${type.ifBlank { "an unknown type" }}).")
            }
        }
    }

    private fun parse(link: String): HttpUrl {
        val t = link.trim()
        if (t.isEmpty()) throw ImageLinkException("Paste a link first.")
        val withScheme = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t)) t else "https://$t"
        val url = withScheme.toHttpUrlOrNull() ?: throw ImageLinkException("Only web links (https://…) can be fetched.")
        return secure(url)
    }

    private fun secure(url: HttpUrl): HttpUrl = if (upgradeToHttps && !url.isHttps) url.newBuilder().scheme("https").build() else url

    private fun get(url: HttpUrl): Response {
        val req = Request.Builder().url(url).header("Accept", "image/avif,image/webp,image/*,text/html;q=0.8,*/*;q=0.5")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android) StudioReferenceFetcher/1.0").build()
        val res = try { client.newCall(req).execute() } catch (e: IOException) {
            throw ImageLinkException("Couldn't reach that link. Check the connection and the address, then try again.")
        }
        if (!res.isSuccessful) { res.close(); throw ImageLinkException("The site refused the request (HTTP ${res.code}). Try the image's own link instead.") }
        return res
    }

    private fun image(res: Response, type: String): ByteArray {
        if (!type.startsWith("image/")) throw ImageLinkException("The page's preview link isn't an image.")
        if ("svg" in type) throw ImageLinkException("SVG drawings can't be used as references. Pick a JPEG, PNG or WebP image.")
        val bytes = read(res, maxBytes, allowTruncate = false)
        if (!looksLikeImage(bytes)) throw ImageLinkException("That link didn't return a readable image.")
        return bytes
    }

    private fun read(res: Response, limit: Int, allowTruncate: Boolean): ByteArray {
        val body = res.body ?: throw ImageLinkException("The link returned nothing.")
        val declared = body.contentLength()
        if (!allowTruncate && declared > limit) throw ImageLinkException("That image is larger than ${limit / 1024 / 1024} MB.")
        val out = ByteArrayOutputStream()
        body.byteStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                if (out.size() + n > limit) {
                    if (allowTruncate) { out.write(buf, 0, limit - out.size()); break }
                    throw ImageLinkException("That image is larger than ${limit / 1024 / 1024} MB.")
                }
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    companion object {
        private const val HTML_LIMIT = 1024 * 1024
        private val META = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
        private val LINK = Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE)
        private fun attr(tag: String, name: String): String? =
            Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)
                ?.let { m -> m.groupValues.drop(2).firstOrNull { it.isNotEmpty() } }

        private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">").trim()

        /** The page's preview image, in the order sites usually fill them in. */
        fun previewImage(html: String): String? {
            val metas = META.findAll(html).map { it.value }.toList()
            for (key in listOf("og:image:secure_url", "og:image:url", "og:image", "twitter:image", "twitter:image:src")) {
                metas.firstOrNull { (attr(it, "property") ?: attr(it, "name"))?.equals(key, true) == true }
                    ?.let { attr(it, "content") }?.let(::unescape)?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return LINK.findAll(html).map { it.value }.firstOrNull { attr(it, "rel")?.equals("image_src", true) == true }
                ?.let { attr(it, "href") }?.let(::unescape)?.takeIf { it.isNotBlank() }
        }

        fun title(html: String): String? {
            val metas = META.findAll(html).map { it.value }.toList()
            return metas.firstOrNull { (attr(it, "property") ?: attr(it, "name"))?.equals("og:title", true) == true }
                ?.let { attr(it, "content") }?.let(::unescape)?.takeIf { it.isNotBlank() }?.take(200)
                ?: Regex("<title[^>]*>([^<]{1,300})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let(::unescape)?.take(200)
        }

        /** JPEG, PNG, GIF, WebP, BMP or HEIF/AVIF by their magic bytes. */
        fun looksLikeImage(b: ByteArray): Boolean {
            fun at(i: Int, vararg v: Int) = b.size >= i + v.size && v.indices.all { (b[i + it].toInt() and 0xFF) == v[it] }
            return at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 0x50, 0x4E, 0x47) || at(0, 0x47, 0x49, 0x46, 0x38) ||
                (at(0, 0x52, 0x49, 0x46, 0x46) && at(8, 0x57, 0x45, 0x42, 0x50)) || at(0, 0x42, 0x4D) ||
                at(4, 0x66, 0x74, 0x79, 0x70)
        }
    }
}
