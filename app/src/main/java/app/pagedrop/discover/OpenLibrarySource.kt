package app.pagedrop.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Open Library adds catalog/borrowing results; only open Archive editions can become downloads. */
internal class OpenLibrarySource(private val http: DiscoverHttp) : BookSource {
    override val kind = SourceKind.OPEN_LIBRARY
    override val name = "Open Library"

    override fun search(query: String): List<BookResult> {
        val fields = "key,title,author_name,cover_i,first_publish_year,language,ebook_access,availability"
        val url = "https://openlibrary.org/search.json?q=${TextUtil.enc(query)}&has_fulltext=true&limit=12&fields=$fields"
        return parse(http.get(url, accept = "application/json").requireOk(name).body)
    }

    companion object {
        fun parse(body: String): List<BookResult> {
            val docs = try { Json.parseToJsonElement(body).jsonObject["docs"] as? JsonArray } catch (e: Exception) {
                throw SourceUnavailableException("Open Library sent an unreadable answer")
            } ?: throw SourceUnavailableException("Open Library couldn't search right now")
            return docs.mapNotNull { item ->
                val o = item as? JsonObject ?: return@mapNotNull null
                val key = o["key"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                if (!Regex("(?:/works/)?OL[0-9]+W").matches(key)) return@mapNotNull null
                val access = o["ebook_access"]?.jsonPrimitive?.contentOrNull
                if (access !in setOf("public", "borrowable", "printdisabled")) return@mapNotNull null
                val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val availability = o["availability"] as? JsonObject
                val archive = availability?.takeIf {
                    it["status"]?.jsonPrimitive?.contentOrNull == "open" &&
                        it["is_restricted"]?.jsonPrimitive?.booleanOrNull != true
                }?.get("identifier")?.jsonPrimitive?.contentOrNull?.takeIf { Regex("[A-Za-z0-9_.-]+").matches(it) }
                val cover = o["cover_i"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                BookResult(
                    id = "open_library:${key.substringAfterLast('/')}", source = SourceKind.OPEN_LIBRARY, sourceName = "Open Library",
                    title = title, author = (o["author_name"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.joinToString(", ").orEmpty(),
                    year = o["first_publish_year"]?.jsonPrimitive?.intOrNull,
                    // Work-level language arrays span translations; no single edition language is known here.
                    coverUrl = cover?.let { "https://covers.openlibrary.org/b/id/$it-M.jpg?default=false" },
                    description = if (archive != null) "Open edition. Checking downloadable formats is available in PageDrop."
                        else "Read or borrow on Open Library. Availability and sign-in requirements vary by edition.",
                    options = emptyList(), needsResolve = archive != null,
                    archiveIdentifier = archive, websiteUrl = "https://openlibrary.org/works/${key.substringAfterLast('/')}",
                )
            }.take(12)
        }
    }
}
