package app.pagedrop.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import org.jsoup.Jsoup

/** Public title search. File links are read from the selected edition, never guessed from its ID. */
internal class FadedPageSource(private val http: DiscoverHttp) : BookSource {
    override val kind = SourceKind.FADED_PAGE
    override val name = "Faded Page"

    override fun search(query: String): List<BookResult> {
        val q = TextUtil.cleanQuery(query)
        if (q.isEmpty()) return emptyList()
        // The site's form uses POST. GET silently returns an unrelated, truncated catalog.
        val reply = http.postForm("$BASE/csearc2.php", mapOf("title" to q, "plang" to "en", "sort" to "auto")).requireOk(name)
        return parseSearch(reply.body)
    }

    fun resolve(result: BookResult): BookResult {
        val id = result.id.removePrefix("faded_page:")
        if (!ID.matches(id)) throw SourceUnavailableException("Faded Page couldn't identify this edition")
        val reply = http.get("$BASE/showbook.php?pid=$id", accept = "text/html").requireOk(name)
        return result.copy(options = parseOptions(id, reply.body), needsResolve = false)
    }

    companion object {
        private const val BASE = "https://www.fadedpage.com"
        private val ID = Regex("[0-9]{8}")

        fun parseSearch(body: String): List<BookResult> {
            val rows = try { Json.parseToJsonElement(body).jsonObject["rows"] as? JsonArray } catch (e: Exception) {
                throw SourceUnavailableException("Faded Page sent an unreadable answer")
            } ?: throw SourceUnavailableException("Faded Page couldn't search right now")
            return rows.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val id = o["pid"]?.jsonPrimitive?.contentOrNull?.takeIf(ID::matches) ?: return@mapNotNull null
                val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val authors = (o["authors"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "author" }
                    .mapNotNull { it["realname"]?.jsonPrimitive?.contentOrNull }.map(TextUtil::displayAuthor)
                val cover = o["cover"]?.jsonPrimitive?.contentOrNull?.let { "$BASE/".toHttpUrlOrNull()?.resolve(it) }
                    ?.takeIf { it.isHttps && it.host == "www.fadedpage.com" }?.toString()
                BookResult(
                    id = "faded_page:$id", source = SourceKind.FADED_PAGE, sourceName = "Faded Page",
                    title = title, author = authors.joinToString(", "), language = TextUtil.shortLanguage(o["lang"]?.jsonPrimitive?.contentOrNull),
                    year = o["first_publication"]?.jsonPrimitive?.intOrNull, coverUrl = cover,
                    description = o["description"]?.jsonPrimitive?.contentOrNull?.let(TextUtil::stripTags),
                    options = emptyList(), needsResolve = true, websiteUrl = "$BASE/showbook.php?pid=$id",
                )
            }.distinctBy { it.id }.take(12)
        }

        fun parseOptions(id: String, html: String): List<DownloadOption> =
            Jsoup.parse(html, BASE).select("a[href]").mapNotNull { link ->
                val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
                if (!url.isHttps || url.host != "www.fadedpage.com" || url.encodedPath != "/link.php") return@mapNotNull null
                val file = url.queryParameter("file") ?: return@mapNotNull null
                if (!Regex("$id(?:-[a-z0-9]+)?\\.(epub|mobi|pdf|txt)").matches(file)) return@mapNotNull null
                val format = OpdsParser.formatOf(null, file) ?: return@mapNotNull null
                DownloadOption(format, url.toString(), label = format.name)
            }.distinctBy { it.format }
    }
}

/** Faded Page requires the session created by the book page when following its download links. */
internal class FadedPageCookieJar : CookieJar {
    private val sessions = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!url.isHttps || url.host != "www.fadedpage.com") return
        for (cookie in cookies.filter { it.name == "PHPSESSID" && it.matches(url) }) {
            sessions.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) sessions += cookie
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        sessions.removeAll { it.expiresAt <= System.currentTimeMillis() }
        if (!url.isHttps || url.host != "www.fadedpage.com") return emptyList()
        return sessions.filter { it.matches(url) }
    }
}
