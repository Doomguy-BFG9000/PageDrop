package app.pagedrop.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Unicode text search. Export completeness depends on each book's chapter/transclusion structure. */
internal class WikisourceSource(private val http: DiscoverHttp, override val kind: SourceKind = SourceKind.HINDI_WIKISOURCE) : BookSource {
    init { require(kind == SourceKind.HINDI_WIKISOURCE || kind == SourceKind.ENGLISH_WIKISOURCE) }
    private val language = if (kind == SourceKind.HINDI_WIKISOURCE) "hi" else "en"
    override val name = if (language == "hi") "Hindi Wikisource" else "English Wikisource"

    override fun search(query: String): List<BookResult> {
        val url = "https://$language.wikisource.org/w/api.php?action=query&list=search&srnamespace=0&srlimit=30" +
            "&format=json&srsearch=${TextUtil.enc(query)}"
        return parse(http.get(url, accept = "application/json").requireOk(name).body, kind)
    }

    companion object {
        fun parse(body: String, kind: SourceKind = SourceKind.HINDI_WIKISOURCE): List<BookResult> {
            val language = if (kind == SourceKind.HINDI_WIKISOURCE) "hi" else "en"
            val name = if (language == "hi") "Hindi Wikisource" else "English Wikisource"
            val root = try { Json.parseToJsonElement(body).jsonObject } catch (e: Exception) {
                throw SourceUnavailableException("$name sent an unreadable answer")
            }
            val hits = (root["query"] as? JsonObject)?.get("search") as? JsonArray
                ?: throw SourceUnavailableException("$name couldn't search right now")
            return hits.mapNotNull { hit ->
                val o = hit as? JsonObject ?: return@mapNotNull null
                val title = o["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && '/' !in it && ':' !in it }
                    ?: return@mapNotNull null
                val page = TextUtil.enc(title).replace("+", "%20")
                BookResult(
                    id = "${kind.name.lowercase()}:${o["pageid"]?.jsonPrimitive?.contentOrNull ?: title}",
                    source = kind, sourceName = name,
                    title = title, author = "", language = language,
                    description = "Read on $name. Check the book's Download menu for export formats and completeness.",
                    options = emptyList(),
                    websiteUrl = "https://$language.wikisource.org/wiki/$page",
                )
            }.take(12)
        }
    }
}
