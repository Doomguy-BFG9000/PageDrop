package app.pagedrop.metadata

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class BookMetadataCandidate(
    val title: String,
    val author: String,
    val isbn: String? = null,
    val coverUrl: String? = null,
    val publisher: String? = null,
    val year: Int? = null,
    val source: Source = Source.GOOGLE_BOOKS,
    /** 0..1 match score against the query; filled in by [BookMetadataRanker]. */
    val confidence: Float = 0f,
) {
    enum class Source { GOOGLE_BOOKS, OPEN_LIBRARY, MERGED }

    val isHighConfidence: Boolean get() = confidence >= BookMetadataRanker.HIGH_CONFIDENCE
}

/** Outcome of a catalog lookup. Distinguishes "nothing found" from "could not ask". */
sealed interface MetadataLookupResult {
    /**
     * @property candidates ranked best first.
     * @property best the top candidate when it is a confident match (safe to pre-fill, still ask the user
     * to confirm); null when only weak matches exist.
     * @property degraded one of the sources failed, so results may be incomplete.
     */
    data class Matches(
        val candidates: List<BookMetadataCandidate>,
        val best: BookMetadataCandidate?,
        val degraded: Boolean = false,
    ) : MetadataLookupResult

    /** The catalogs answered and nothing matches (or the query had no searchable words). */
    data object NoMatches : MetadataLookupResult

    /** Offline, timed out, or every catalog errored/rate-limited. Say "try again", not "no matches". */
    data class Unavailable(val offline: Boolean, val message: String) : MetadataLookupResult
}

class MetadataUnavailableException(message: String, val offline: Boolean) : IOException(message)

/** Minimal HTTP seam so lookups are unit-testable without a network. Throws [IOException] when offline. */
fun interface MetadataHttp {
    fun get(url: String): Reply

    data class Reply(val code: Int, val body: String?) {
        val isSuccess: Boolean get() = code in 200..299
    }
}

internal class OkHttpMetadataHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build(),
) : MetadataHttp {
    override fun get(url: String): MetadataHttp.Reply {
        val request = Request.Builder().url(url)
            .header("User-Agent", "PageDrop/1.0 (Android; book metadata lookup)")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            return MetadataHttp.Reply(response.code, response.body?.string())
        }
    }

    val rawClient: OkHttpClient get() = client
}

/**
 * Catalog lookup against Open Library (primary, no key, generous limits) and Google Books (extra, optional).
 * Google Books is only contacted while [useGoogleBooks] returns true.
 * All network work happens on [Dispatchers.IO]; every failure mode is returned, never thrown.
 */
class BookMetadataSearcher(
    private val http: MetadataHttp,
    private val useGoogleBooks: () -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface SourceOutcome {
        data class Ok(val items: List<BookMetadataCandidate>) : SourceOutcome
        data class Failed(val offline: Boolean, val message: String) : SourceOutcome
    }

    suspend fun lookup(title: String, author: String = ""): MetadataLookupResult =
        lookup(LookupQuery.from(title, author))

    suspend fun lookup(query: LookupQuery): MetadataLookupResult = withContext(Dispatchers.IO) {
        if (!query.isSearchable) return@withContext MetadataLookupResult.NoMatches

        val (open, google) = coroutineScope {
            val o = async { openLibrary(query) }
            val g = async { if (useGoogleBooks()) googleBooks(query) else null }
            o.await() to g.await()
        }

        val outcomes = listOfNotNull(open, google)
        val okItems = outcomes.filterIsInstance<SourceOutcome.Ok>().flatMap { it.items }
        val failures = outcomes.filterIsInstance<SourceOutcome.Failed>()
        val merged = merge(okItems)
        val ranked = BookMetadataRanker.rank(query, merged)

        when {
            ranked.isNotEmpty() -> MetadataLookupResult.Matches(
                candidates = ranked.take(MAX_RESULTS),
                best = ranked.first().takeIf { it.isHighConfidence },
                degraded = failures.isNotEmpty(),
            )
            // Open Library is the reliable source: if it answered, the answer is "no matches".
            open is SourceOutcome.Ok -> MetadataLookupResult.NoMatches
            google is SourceOutcome.Ok && failures.isEmpty() -> MetadataLookupResult.NoMatches
            else -> {
                val first = failures.firstOrNull()
                MetadataLookupResult.Unavailable(
                    offline = failures.isNotEmpty() && failures.all { it.offline },
                    message = first?.message ?: "Book catalogs are unavailable",
                )
            }
        }
    }

    // ── Sources ──────────────────────────────────────────────────────────────

    private fun openLibrary(query: LookupQuery): SourceOutcome {
        fun run(build: (okhttp3.HttpUrl.Builder) -> Unit): SourceOutcome =
            fetch("Open Library", OPEN_LIBRARY_SEARCH.toHttpUrl().newBuilder().also(build)
                .addQueryParameter("fields", "title,author_name,isbn,cover_i,publisher,first_publish_year")
                .addQueryParameter("limit", "8")
                .build().toString(), ::parseOpenLibrary)

        val text = listOf(query.title, query.author).filter { it.isNotBlank() }.joinToString(" ")
        val first = if (query.isbn != null) run { it.addQueryParameter("isbn", query.isbn) }
        else run { it.addQueryParameter("q", text) }
        if (first is SourceOutcome.Ok && first.items.isEmpty() && query.isbn != null && text.isNotBlank()) {
            return run { it.addQueryParameter("q", text) }
        }
        if (first is SourceOutcome.Ok && first.items.isEmpty() && query.isbn == null && query.author.isNotBlank() && query.title.isNotBlank()) {
            return run { it.addQueryParameter("q", query.title) }
        }
        return first
    }

    private fun googleBooks(query: LookupQuery): SourceOutcome {
        val text = listOf(query.title, query.author).filter { it.isNotBlank() }.joinToString(" ")
        val q = if (query.isbn != null) "isbn:${query.isbn}" else text
        if (q.isBlank()) return SourceOutcome.Ok(emptyList())
        val url = GOOGLE_VOLUMES.toHttpUrl().newBuilder()
            .addQueryParameter("q", q)
            .addQueryParameter("maxResults", "8")
            .addQueryParameter("printType", "books")
            .build().toString()
        return fetch("Google Books", url, ::parseGoogleBooks)
    }

    private fun fetch(name: String, url: String, parse: (String) -> List<BookMetadataCandidate>): SourceOutcome =
        try {
            val reply = http.get(url)
            when {
                reply.isSuccess -> SourceOutcome.Ok(parse(reply.body.orEmpty()))
                reply.code == 429 -> SourceOutcome.Failed(false, "$name is rate-limiting requests")
                else -> SourceOutcome.Failed(false, "$name returned HTTP ${reply.code}")
            }
        } catch (e: IOException) {
            SourceOutcome.Failed(true, "Could not reach $name: ${e.message ?: "network error"}")
        } catch (e: kotlinx.serialization.SerializationException) {
            SourceOutcome.Failed(false, "$name sent an unreadable response")
        } catch (e: IllegalArgumentException) {
            SourceOutcome.Failed(false, "$name sent an unreadable response")
        }

    // ── Parsing (internal for tests) ─────────────────────────────────────────

    internal fun parseOpenLibrary(body: String): List<BookMetadataCandidate> {
        val docs = json.parseToJsonElement(body).jsonObject["docs"]?.jsonArray ?: return emptyList()
        return docs.mapNotNull { element ->
            val doc = element.jsonObject
            val title = doc.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val authors = doc["author_name"].strings().take(3).joinToString(" & ")
            val coverId = doc["cover_i"]?.jsonPrimitive?.intOrNull
            BookMetadataCandidate(
                title = title,
                author = authors.ifBlank { UNKNOWN_AUTHOR },
                isbn = doc["isbn"].strings().firstOrNull { it.length == 13 } ?: doc["isbn"].strings().firstOrNull(),
                coverUrl = coverId?.takeIf { it > 0 }?.let { "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false" },
                publisher = doc["publisher"].strings().firstOrNull(),
                year = doc["first_publish_year"]?.jsonPrimitive?.intOrNull,
                source = BookMetadataCandidate.Source.OPEN_LIBRARY,
            )
        }
    }

    internal fun parseGoogleBooks(body: String): List<BookMetadataCandidate> {
        val items = json.parseToJsonElement(body).jsonObject["items"]?.jsonArray ?: return emptyList()
        return items.mapNotNull { element ->
            val info = element.jsonObject["volumeInfo"]?.jsonObject ?: return@mapNotNull null
            val title = info.string("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val authors = info["authors"].strings().take(3).joinToString(" & ")
            val ids = info["industryIdentifiers"]?.jsonArray.orEmpty().mapNotNull { id ->
                id.jsonObject.string("identifier")
            }
            BookMetadataCandidate(
                title = title,
                author = authors.ifBlank { UNKNOWN_AUTHOR },
                isbn = ids.firstOrNull { it.length == 13 } ?: ids.firstOrNull(),
                coverUrl = info["imageLinks"]?.jsonObject?.let(::bestGoogleCover),
                publisher = info.string("publisher"),
                year = info.string("publishedDate")?.take(4)?.toIntOrNull(),
                source = BookMetadataCandidate.Source.GOOGLE_BOOKS,
            )
        }
    }

    // ── Merge ────────────────────────────────────────────────────────────────

    internal fun merge(items: List<BookMetadataCandidate>): List<BookMetadataCandidate> {
        val byKey = LinkedHashMap<String, BookMetadataCandidate>()
        for (item in items) {
            val key = dedupeKey(item)
            val existing = byKey[key]
            byKey[key] = if (existing == null) item else combine(existing, item)
        }
        return byKey.values.toList()
    }

    private fun dedupeKey(c: BookMetadataCandidate): String {
        val title = BookNameCleaner.normalizeTokens(c.title.substringBefore(':')).filterNot { it in setOf("the", "a", "an") }
        val surname = BookNameCleaner.normalizeTokens(c.author.substringBefore(" & ")).lastOrNull().orEmpty()
        return title.joinToString(" ") + "|" + surname
    }

    private fun combine(a: BookMetadataCandidate, b: BookMetadataCandidate): BookMetadataCandidate {
        // Prefer Open Library for the cover (stable URLs, full-size) and fall back to Google.
        val ol = if (a.source == BookMetadataCandidate.Source.OPEN_LIBRARY) a else b
        val other = if (ol === a) b else a
        return BookMetadataCandidate(
            title = ol.title,
            author = if (ol.author != UNKNOWN_AUTHOR) ol.author else other.author,
            isbn = ol.isbn ?: other.isbn,
            coverUrl = ol.coverUrl ?: other.coverUrl,
            publisher = ol.publisher ?: other.publisher,
            year = ol.year ?: other.year,
            source = BookMetadataCandidate.Source.MERGED,
        )
    }

    companion object {
        const val UNKNOWN_AUTHOR = "Unknown Author"
        private const val MAX_RESULTS = 8
        private const val OPEN_LIBRARY_SEARCH = "https://openlibrary.org/search.json"
        private const val GOOGLE_VOLUMES = "https://www.googleapis.com/books/v1/volumes"

        private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

        private fun kotlinx.serialization.json.JsonElement?.strings(): List<String> =
            (this as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }

        /** Picks the largest Google image link, forces https, and asks for the biggest zoom without the curl effect. */
        internal fun bestGoogleCover(images: JsonObject): String? {
            val raw = listOf("extraLarge", "large", "medium", "small", "thumbnail", "smallThumbnail")
                .firstNotNullOfOrNull { images[it]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank) }
                ?: return null
            var url = raw.replace("http://", "https://").replace("&edge=curl", "")
            if (Regex("([?&])zoom=\\d").containsMatchIn(url)) url = url.replace(Regex("([?&])zoom=\\d"), "$1zoom=0")
            return url
        }
    }
}

/**
 * App-wide entry point. Network calls are always made off the main thread.
 *
 * Preferred API: [lookup]. [search] is kept for older callers.
 */
object BookMetadataLookup {
    private val okHttp = OkHttpMetadataHttp()

    /** Set once at startup from [MetadataSettings]; until then the build's default applies. */
    @Volatile
    private var settings: MetadataSettings? = null
    private val searcher = BookMetadataSearcher(okHttp) {
        settings?.useGoogleBooks?.value ?: MetadataSettings.defaultUseGoogleBooks()
    }

    fun init(settings: MetadataSettings) {
        this.settings = settings
    }

    /** Ranked lookup; see [MetadataLookupResult]. Cleans file-name style titles itself. */
    suspend fun lookup(title: String, author: String = ""): MetadataLookupResult = searcher.lookup(title, author)

    /** Ranked lookup for an already-built [LookupQuery] (no further cleaning). */
    suspend fun lookup(query: LookupQuery): MetadataLookupResult = searcher.lookup(query)

    /** Compat wrapper: success(emptyList()) means "no matches"; failure(MetadataUnavailableException) means offline/error. */
    suspend fun search(title: String, author: String): Result<List<BookMetadataCandidate>> =
        when (val result = lookup(title, author)) {
            is MetadataLookupResult.Matches -> Result.success(result.candidates)
            MetadataLookupResult.NoMatches -> Result.success(emptyList())
            is MetadataLookupResult.Unavailable ->
                Result.failure(MetadataUnavailableException(result.message, result.offline))
        }

    /** Downloads a cover and returns its bytes, or null if unreachable or not a real image. Safe on any thread. */
    suspend fun fetchCover(coverUrl: String): ByteArray? = withContext(Dispatchers.IO) { downloadCoverBytes(coverUrl) }

    /** Blocking cover download to [destination]; call from a background thread (the ViewModel uses Dispatchers.IO). */
    fun downloadCover(coverUrl: String, destination: File): Boolean {
        val bytes = downloadCoverBytes(coverUrl) ?: return false
        return runCatching { destination.writeBytes(bytes); true }.getOrDefault(false)
    }

    /** Blocking; rejects tiny placeholders (Open Library serves a 1x1 gif) and non-image payloads. */
    fun downloadCoverBytes(coverUrl: String): ByteArray? = try {
        val request = Request.Builder().url(coverUrl.replace("http://", "https://"))
            .header("User-Agent", "PageDrop/1.0 (Android; book metadata lookup)").build()
        okHttp.rawClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.bytes()?.takeIf { CoverImages.isUsableCover(it) }
        }
    } catch (e: Exception) {
        null
    }
}

object CoverImages {
    private const val MIN_COVER_BYTES = 2_000

    fun isJpeg(b: ByteArray) = b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() && b[2] == 0xFF.toByte()
    fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()
    fun isGif(b: ByteArray) = b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte()

    fun isImage(b: ByteArray) = isJpeg(b) || isPng(b) || isGif(b)

    /** A real cover: a JPEG/PNG and not a few-byte placeholder. */
    fun isUsableCover(b: ByteArray) = b.size >= MIN_COVER_BYTES && (isJpeg(b) || isPng(b))
}
