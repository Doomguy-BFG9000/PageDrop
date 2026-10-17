package app.pagedrop.metadata

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What the Add sheet should pre-fill and offer, derived from the file itself first and the catalogs second. */
data class Suggestion(
    /** Pre-fill value; null when nothing trustworthy (or even guessable) was found. */
    val title: String?,
    val author: String?,
    /** Where [title] / [author] came from. */
    val source: Source,
    /** Ranked catalog matches (best first); empty when none or unavailable. */
    val candidates: List<BookMetadataCandidate> = emptyList(),
    /** Confident catalog match (safe to offer as one-tap apply); null when only weak matches exist. */
    val best: BookMetadataCandidate? = null,
    /** Cover read from the file itself; preferred over [coverUrl]. */
    val coverBytes: ByteArray? = null,
    val coverUrl: String? = null,
    /** Catalogs could not be reached (say "try again", not "no matches"). */
    val unavailable: Boolean = false,
    val offline: Boolean = false,
    val message: String? = null,
    /** Network queries actually sent, in order (for debugging/tests). */
    val queriesTried: List<String> = emptyList(),
) {
    enum class Source {
        /** Title/author stored inside the file (high trust). */
        EMBEDDED,
        /** Guessed from the PDF title page (low trust). */
        FIRST_PAGE,
        /** Cleaned file name (lowest trust). */
        FILENAME,
        /** Confident catalog match. */
        ONLINE,
    }

    /** True when [title] is trustworthy enough to apply without asking (embedded or confident online match). */
    val isTrusted: Boolean get() = title != null && (source == Source.EMBEDDED || source == Source.ONLINE)
}

/**
 * Combines embedded metadata, the PDF title page and the cleaned file name into the best lookup queries,
 * then asks the catalogs (at most [MAX_QUERIES] requests, retrying only on "no matches").
 *
 * [lookup] is injectable so tests need no network.
 */
class MetadataSuggester(
    private val lookup: suspend (LookupQuery) -> MetadataLookupResult = { BookMetadataLookup.lookup(it) },
) {
    private class Candidate(val query: LookupQuery, val label: String)

    suspend fun suggest(file: File, format: String, displayName: String): Suggestion = withContext(Dispatchers.IO) {
        val embedded = EmbeddedMetadataReader.read(file, format)
        val isPdf = format.equals("PDF", ignoreCase = true) || file.extension.equals("pdf", ignoreCase = true)
        val firstPage = if (embedded.title == null && isPdf) PdfFirstPageGuess.guess(file) else null
        val fileName = BookNameCleaner.parse(displayName)

        val queries = buildList {
            embedded.title?.let { add(Candidate(LookupQuery(it, embedded.author.orEmpty()), "embedded")) }
            firstPage?.let { add(Candidate(LookupQuery(it.title, it.author.orEmpty()), "first-page")) }
            if (fileName.isSearchable) {
                add(Candidate(LookupQuery.from(displayName), "filename"))
                BookNameCleaner.extraQueries(displayName).forEach { split ->
                    add(Candidate(LookupQuery(split, fileName.author.orEmpty(), lossy = true, guessedFromFilename = true), "filename-split"))
                }
            }
        }.distinctBy { c -> BookNameCleaner.normalizeTokens(c.query.title + " " + c.query.author).joinToString(" ") + "|" + c.query.isbn }

        var result: MetadataLookupResult = MetadataLookupResult.NoMatches
        val tried = mutableListOf<String>()
        for (c in queries.take(MAX_QUERIES)) {
            tried += listOf(c.query.title, c.query.author).filter { it.isNotBlank() }.joinToString(" | ")
            result = lookup(c.query)
            if (result !is MetadataLookupResult.NoMatches) break
        }

        val matches = result as? MetadataLookupResult.Matches
        val best = matches?.best
        val unavailable = result as? MetadataLookupResult.Unavailable

        var title: String?
        var author: String?
        val source: Suggestion.Source
        when {
            embedded.title != null -> {
                title = embedded.title
                author = embedded.author ?: best?.author?.takeUnless { BookNameCleaner.isPlaceholderAuthor(it) }
                    ?.takeIf { BookMetadataRanker.titleSimilarity(embedded.title, best.title) >= 0.8 }
                source = Suggestion.Source.EMBEDDED
            }
            best != null -> {
                title = best.title
                author = best.author.takeUnless { BookNameCleaner.isPlaceholderAuthor(it) }
                source = Suggestion.Source.ONLINE
            }
            firstPage != null -> {
                title = firstPage.title
                author = firstPage.author
                source = Suggestion.Source.FIRST_PAGE
            }
            else -> {
                title = fileName.title.takeIf { it.isNotBlank() }
                author = fileName.author
                source = Suggestion.Source.FILENAME
            }
        }
        if (embedded.title == null && embedded.author != null && author == null) author = embedded.author

        Suggestion(
            title = title,
            author = author,
            source = source,
            candidates = matches?.candidates.orEmpty(),
            best = best,
            coverBytes = embedded.cover,
            coverUrl = (best ?: matches?.candidates?.firstOrNull()?.takeIf { embedded.title != null })?.coverUrl,
            unavailable = unavailable != null,
            offline = unavailable?.offline == true,
            message = unavailable?.message,
            queriesTried = tried,
        )
    }

    companion object {
        const val MAX_QUERIES = 3

        /** Default instance backed by Open Library + Google Books. */
        val default = MetadataSuggester()

        suspend fun suggest(file: File, format: String, displayName: String): Suggestion = default.suggest(file, format, displayName)

        /** Copies [uri] into the cache dir so format readers can use a [File]; caller deletes it. Null if unreadable. */
        suspend fun copyToCache(context: Context, uri: Uri, maxBytes: Long = 300L * 1024 * 1024): File? = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "suggest").apply { mkdirs() }
                val name = displayName(context, uri)?.let { File(it).name.take(80) }?.takeIf { it.isNotBlank() } ?: "book"
                val out = File.createTempFile("pd_", "_$name", dir)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) { out.delete(); return@withContext null }
                            o.write(buf, 0, n)
                        }
                    }
                } ?: run { out.delete(); return@withContext null }
                out
            } catch (e: Exception) {
                android.util.Log.w("MetadataSuggester", "Could not copy $uri for metadata detection", e)
                null
            }
        }

        fun displayName(context: Context, uri: Uri): String? = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment
        } catch (e: Exception) {
            uri.lastPathSegment
        }
    }
}
