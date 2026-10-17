package app.pagedrop.discover

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.util.UUID

/** Live access to WebView's cookie store, scoped by URL even after a download redirects. */
class BrowserDownloadSession(
    val userAgent: String,
    val referer: String?,
    private val readCookies: (String) -> String?,
    private val writeCookie: (String, String) -> Unit = { _, _ -> },
) {
    internal val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (!url.isHttps) return emptyList()
            return readCookies(url.toString()).orEmpty().split(';').mapNotNull {
                Cookie.parse(url, it.trim())
            }
        }

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (url.isHttps) cookies.forEach { writeCookie(url.toString(), it.toString()) }
        }
    }

    internal fun refererFor(url: HttpUrl): String? {
        val from = referer?.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return null
        if (!url.isHttps) return null
        val clean = from.newBuilder().username("").password("").fragment(null)
        return if (from.host == url.host && from.port == url.port) clean.build().toString()
        else clean.encodedPath("/").query(null).build().toString()
    }
}

/** Converts a browser download event to the same format picker / import flow as catalog results. */
object BrowserDownloads {
    fun capture(
        url: String,
        contentDisposition: String?,
        mimeType: String?,
        sourceName: String,
        session: BrowserDownloadSession,
        sizeBytes: Long = -1,
    ): BookResult? {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() } ?: return null
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase()
        if (mime == "text/html" || mime == "application/xhtml+xml") return null
        val fileName = fileName(contentDisposition) ?: parsed.pathSegments.lastOrNull().orEmpty()
        val format = OpdsParser.formatOf(mime, fileName) ?: return null
        val (title, author) = metadataFromFileName(fileName, format)
        return BookResult(
            id = "website:${UUID.randomUUID()}", source = SourceKind.WEBSITE, sourceName = sourceName,
            title = title, author = author, websiteUrl = session.referer,
            options = listOf(DownloadOption(format, parsed.toString(), sizeBytes.takeIf { it > 0 }, browserSession = session)),
        )
    }

    /** Only intercept explicit book paths. PHP/token routes are handled by WebView's DownloadListener. */
    fun isBookLink(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull()?.takeIf { it.isHttps } ?: return false
        return Regex("(?i)\\.(epub|mobi|azw3|pdf|txt)$").containsMatchIn(parsed.pathSegments.lastOrNull().orEmpty())
    }

    private fun fileName(disposition: String?): String? {
        if (disposition == null) return null
        Regex("(?i)filename\\*=UTF-8''([^;]+)").find(disposition)?.groupValues?.get(1)?.let {
            return runCatching { URLDecoder.decode(it.trim().replace("+", "%2B"), "UTF-8") }.getOrNull()
        }
        return Regex("(?i)filename=\\s*(?:\"([^\"]+)\"|([^;]+))").find(disposition)?.let {
            it.groupValues[1].ifBlank { it.groupValues[2].trim() }
        }
    }

    private fun metadataFromFileName(name: String, format: BookFormat): Pair<String, String> {
        val base = name.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').trim()
        // Anna's published filename: Title -- Author -- Edition -- MD5 -- Anna’s Archive.ext.
        val anna = base.split(" -- ")
        if (anna.size >= 4 && anna.last().contains("Archive", ignoreCase = true)) {
            return anna[0].take(300).ifBlank { "Downloaded book" } to anna[1].take(200)
        }
        val parts = base.split(" - ", limit = 2)
        val title = parts[0].replace('_', ' ').trim().take(300)
            .takeUnless { it.isBlank() || it.equals("download", true) || it.matches(Regex("[a-fA-F0-9]{16,}")) }
            ?: "Downloaded ${format.name} book"
        return title to parts.getOrNull(1).orEmpty().take(200)
    }
}
