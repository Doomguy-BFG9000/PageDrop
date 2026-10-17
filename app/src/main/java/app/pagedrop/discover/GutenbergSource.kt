package app.pagedrop.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Project Gutenberg through the public Gutendex mirror of its catalog. */
internal class GutenbergSource(private val http: DiscoverHttp) : BookSource {
    override val kind = SourceKind.GUTENBERG
    override val name = "Project Gutenberg"

    override fun search(query: String): List<BookResult> {
        val reply = http.get("https://gutendex.com/books/?search=${TextUtil.enc(query)}", accept = "application/json")
            .requireOk(name)
        return parse(reply.body)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_RESULTS = 12

        fun parse(body: String): List<BookResult> {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw SourceUnavailableException("Project Gutenberg sent an unreadable answer")
            }
            val results = (root["results"] as? JsonArray) ?: return emptyList()
            return results.mapNotNull { (it as? JsonObject)?.let(::parseBook) }.take(MAX_RESULTS)
        }

        private fun parseBook(o: JsonObject): BookResult? {
            val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return null
            val title = o["title"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (o["copyright"]?.jsonPrimitive?.booleanOrNull == true) return null
            val mediaType = o["media_type"]?.jsonPrimitive?.contentOrNull
            if (mediaType != null && mediaType != "Text") return null

            val authors = (o["authors"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
                .map(TextUtil::displayAuthor).filter { it.isNotEmpty() }
            val formats = (o["formats"] as? JsonObject).orEmpty()
                .mapValues { it.value.jsonPrimitive.contentOrNull.orEmpty() }
            val summary = (o["summaries"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull
                ?.replace(Regex("\\s*\\(This is an automatically generated summary\\.\\)\\s*$"), "")
                ?.trim()?.takeIf { it.isNotEmpty() }
            val language = (o["languages"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.contentOrNull

            return BookResult(
                id = "gutenberg:$id",
                source = SourceKind.GUTENBERG,
                sourceName = "Project Gutenberg",
                title = title,
                author = authors.joinToString(", "),
                year = null,
                language = TextUtil.shortLanguage(language),
                coverUrl = formats["image/jpeg"]?.takeIf { it.startsWith("http") },
                description = summary,
                options = options(id, formats),
            )
        }

        /**
         * Gutendex lists the Kindle file under application/x-mobipocket-ebook, but its URL is ".kf8.images" (AZW3),
         * not classic MOBI. Verified live: ".kf8.images" and ".kindle.images" both redirect to a BOOKMOBI file.
         */
        internal fun options(id: String, formats: Map<String, String>): List<DownloadOption> {
            val out = mutableListOf<DownloadOption>()
            val mobi = formats.entries.firstOrNull { it.key.startsWith("application/x-mobipocket-ebook") }?.value
                ?.takeIf { it.startsWith("http") }
            if (mobi != null) {
                if (mobi.contains(".kf8")) {
                    out += DownloadOption(BookFormat.AZW3, mobi, label = "Kindle (AZW3)")
                    out += DownloadOption(BookFormat.MOBI, "https://www.gutenberg.org/ebooks/$id.kindle.images", label = "Kindle (MOBI)")
                } else {
                    out += DownloadOption(BookFormat.MOBI, mobi, label = "Kindle (MOBI)")
                }
            }
            formats.entries.firstOrNull { it.key.startsWith("application/epub+zip") }?.value
                ?.takeIf { it.startsWith("http") }?.let { epub ->
                    // EPUB 2 converts more predictably than EPUB 3's nav documents.
                    out += DownloadOption(BookFormat.EPUB, epub.replace(".epub3.images", ".epub.images"), label = "EPUB")
                }
            formats.entries.firstOrNull { it.key.startsWith("text/plain") }?.value
                ?.takeIf { it.startsWith("http") && !it.endsWith(".zip") }?.let {
                    out += DownloadOption(BookFormat.TXT, it, label = "Plain text")
                }
            return out
        }
    }
}
