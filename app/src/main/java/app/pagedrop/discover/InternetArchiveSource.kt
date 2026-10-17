package app.pagedrop.discover

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Internet Archive, limited to items anyone may download: no lending-library / print-disabled collections, and
 * (re-checked in [resolve]) `metadata.access-restricted-item` must not be true.
 */
internal class InternetArchiveSource(
    private val http: DiscoverHttp,
    override val kind: SourceKind = SourceKind.INTERNET_ARCHIVE,
) : BookSource {
    override val name = if (kind == SourceKind.DIGITAL_LIBRARY_INDIA) "Digital Library of India" else "Internet Archive"

    override fun search(query: String): List<BookResult> {
        val q = sanitize(query)
        if (q.isBlank()) return emptyList()
        val scope = if (kind == SourceKind.DIGITAL_LIBRARY_INDIA) " AND collection:digitallibraryindia" else ""
        val lucene = "(title:($q) OR creator:($q)) AND mediatype:texts " +
            "AND NOT collection:(inlibrary OR printdisabled OR lendinglibrary) " +
            "AND NOT access-restricted-item:true AND (format:EPUB OR format:\"Text PDF\" OR format:PDF)$scope"
        val url = "https://archive.org/advancedsearch.php?q=${TextUtil.enc(lucene)}" +
            "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=year&fl%5B%5D=language" +
            "&sort%5B%5D=${TextUtil.enc("downloads desc")}&rows=20&output=json"
        return parseSearch(http.get(url, accept = "application/json").requireOk(name).body).map {
            if (kind == SourceKind.INTERNET_ARCHIVE) it else it.copy(
                id = "digital_library_india:${it.archiveIdentifier}", source = kind, sourceName = name,
            )
        }
    }

    /** Fetches `/metadata/<id>` and fills in the download options (empty when the item is access restricted). */
    fun resolve(result: BookResult): BookResult {
        val identifier = result.archiveIdentifier ?: result.id.removePrefix("internet_archive:")
        val reply = http.get("https://archive.org/metadata/${TextUtil.enc(identifier).replace("+", "%20")}", accept = "application/json")
            .requireOk(name)
        return result.copy(options = parseMetadata(identifier, reply.body), needsResolve = false)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val MAX_RESULTS = 12

        internal fun sanitize(q: String): String =
            q.replace(Regex("[+\\-&|!(){}\\[\\]^\"~*?:\\\\/]"), " ").replace(Regex("\\s+"), " ").trim()

        fun parseSearch(body: String): List<BookResult> {
            val docs = try {
                (json.parseToJsonElement(body).jsonObject["response"] as? JsonObject)?.get("docs") as? JsonArray
            } catch (e: Exception) {
                throw SourceUnavailableException("Internet Archive sent an unreadable answer")
            } ?: return emptyList()
            return docs.mapNotNull { (it as? JsonObject)?.let(::parseDoc) }.take(MAX_RESULTS)
        }

        private fun parseDoc(o: JsonObject): BookResult? {
            val id = o["identifier"]?.firstString() ?: return null
            val title = o["title"]?.firstString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val creator = o["creator"]?.firstString().orEmpty()
            // Standard Ebooks mirrors its own catalog here; we already search the original.
            if (creator.contains("Standard eBooks", ignoreCase = true)) return null
            return BookResult(
                id = "internet_archive:$id",
                source = SourceKind.INTERNET_ARCHIVE,
                sourceName = "Internet Archive",
                title = title,
                author = TextUtil.displayAuthor(creator),
                year = TextUtil.yearOf(o["year"]?.firstString()),
                language = TextUtil.shortLanguage(o["language"]?.firstString()),
                coverUrl = "https://archive.org/services/img/$id",
                options = emptyList(),
                needsResolve = true,
                websiteUrl = "https://archive.org/details/${TextUtil.enc(id)}",
                archiveIdentifier = id,
            )
        }

        private fun JsonElement.firstString(): String? = when (this) {
            is JsonArray -> firstOrNull()?.firstString()
            is JsonPrimitive -> contentOrNull
            else -> null
        }

        /** Download options for an item's metadata JSON; empty if restricted. EPUB, then Text PDF / PDF. */
        fun parseMetadata(identifier: String, body: String): List<DownloadOption> {
            val root = try {
                json.parseToJsonElement(body).jsonObject
            } catch (e: Exception) {
                throw SourceUnavailableException("Internet Archive sent an unreadable answer")
            }
            val restricted = (root["metadata"] as? JsonObject)?.get("access-restricted-item")?.firstString()
            if (restricted.equals("true", ignoreCase = true)) return emptyList()
            val collections = (root["metadata"] as? JsonObject)?.get("collection")
            val collectionNames = if (collections is JsonArray) collections.mapNotNull { it.firstString() } else listOfNotNull(collections?.firstString())
            if (collectionNames.any { it.lowercase() in setOf("inlibrary", "printdisabled", "lendinglibrary") }) return emptyList()
            val files = (root["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it["private"]?.firstString().equals("true", true).not() &&
                    it["format"]?.firstString().orEmpty().contains("Encrypted", true).not() }

            fun pick(match: (String, String) -> Boolean): JsonObject? =
                files.firstOrNull { match(it["format"]?.firstString().orEmpty(), it["name"]?.firstString().orEmpty()) }

            val out = mutableListOf<DownloadOption>()
            fun add(file: JsonObject?, format: BookFormat, label: String) {
                val name = file?.get("name")?.firstString() ?: return
                val encoded = name.split('/').joinToString("/") { TextUtil.enc(it).replace("+", "%20") }
                out += DownloadOption(
                    format,
                    "https://archive.org/download/${TextUtil.enc(identifier).replace("+", "%20")}/$encoded",
                    file["size"]?.firstString()?.toLongOrNull(),
                    label,
                )
            }
            add(pick { _, n -> n.endsWith(".azw3", true) }, BookFormat.AZW3, "Kindle")
            add(pick { f, n -> n.endsWith(".mobi", true) && !f.contains("Encrypted", true) }, BookFormat.MOBI, "Kindle (MOBI)")
            add(pick { f, _ -> f == "EPUB" }, BookFormat.EPUB, "EPUB")
            add(pick { f, _ -> f == "Text PDF" } ?: pick { f, _ -> f == "PDF" }, BookFormat.PDF, "PDF")
            return out
        }
    }
}
