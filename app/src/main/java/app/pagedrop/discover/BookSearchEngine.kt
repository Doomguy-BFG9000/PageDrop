package app.pagedrop.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BookSearchEngine internal constructor(
    private val settings: SourceSettings,
    private val http: DiscoverHttp,
    private val sourceTimeoutMs: Long,
) {
    @Inject
    constructor(settings: SourceSettings, http: DiscoverHttp) : this(settings, http, DEFAULT_TIMEOUT_MS)

    private val opdsTemplates = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Searches every enabled source in parallel (per-source timeout), merges, de-duplicates and ranks. Never throws for source failures. */
    suspend fun search(query: String): SearchResults {
        val q = TextUtil.cleanQuery(query)
        if (q.isEmpty()) return SearchResults(query, emptyList(), emptyList())

        val sources = buildSources()
        val outcomes = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        val found = withTimeout(sourceTimeoutMs) { withContext(Dispatchers.IO) { source.search(q) } }
                        SourceOutcome(source, found, null)
                    } catch (e: TimeoutCancellationException) {
                        SourceOutcome(source, emptyList(), SourceError(source.name, false, "${source.name} took too long to answer"))
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SourceOutcome(source, emptyList(), errorFor(source, e))
                    }
                }
            }.awaitAll()
        }
        val merged = mergeAndRank(q, outcomes.flatMap { it.results })
        return SearchResults(query, merged, outcomes.mapNotNull { it.error })
    }

    /** Fills [BookResult.options] when [BookResult.needsResolve] (Internet Archive). Throws [IOException] on network failure. */
    suspend fun resolve(result: BookResult): BookResult {
        if (!result.needsResolve) return result
        return withContext(Dispatchers.IO) {
            when (result.source) {
                SourceKind.INTERNET_ARCHIVE -> try {
                    InternetArchiveSource(http).resolve(result)
                } catch (e: IOException) {
                    throw describe(e, "Internet Archive")
                }
                else -> result.copy(needsResolve = false)
            }
        }
    }

    /** AZW3 > MOBI > EPUB > PDF > TXT. (Stock Kindles read AZW3/MOBI/PDF/TXT; EPUB is converted on import.) */
    fun bestOption(result: BookResult): DownloadOption? = Ranking.bestOption(result.options)

    private fun buildSources(): List<BookSource> {
        val enabled = settings.enabledBuiltIns.value
        val list = mutableListOf<BookSource>()
        if (SourceKind.STANDARD_EBOOKS in enabled) list += StandardEbooksSource(http)
        if (SourceKind.GUTENBERG in enabled) list += GutenbergSource(http)
        if (SourceKind.INTERNET_ARCHIVE in enabled) list += InternetArchiveSource(http)
        settings.catalogs.value.filter { it.enabled }.forEach { c ->
            list += OpdsSource(c, settings.credentialsFor(c.id), http, opdsTemplates)
        }
        return list
    }

    private class SourceOutcome(val source: BookSource, val results: List<BookResult>, val error: SourceError?)

    private fun errorFor(source: BookSource, e: Exception): SourceError {
        val described = describe(e, source.name)
        return SourceError(source.name, described.offline, described.message ?: "${source.name} is unavailable")
    }

    private fun describe(e: Exception, name: String): SourceUnavailableException = when (e) {
        is SourceUnavailableException -> e
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            SourceUnavailableException("$name can't be reached. Check your connection.", offline = true, cause = e)
        is SocketTimeoutException, is java.io.InterruptedIOException ->
            SourceUnavailableException("$name took too long to answer", cause = e)
        else -> SourceUnavailableException("$name is unavailable right now", cause = e)
    }

    private fun mergeAndRank(query: String, all: List<BookResult>): List<BookResult> {
        // Highest-priority source first so it becomes the "winner" of a duplicate group (stable within a source).
        val byPriority = all.sortedBy { Ranking.priority(it.source) }
        val kept = mutableListOf<BookResult>()
        val index = HashMap<String, MutableList<Int>>()
        for (r in byPriority) {
            val key = TextUtil.dedupeKey(r.title, r.author)
            val twinIdx = if (key.startsWith("|")) null else index[key]?.firstOrNull { i ->
                val k = kept[i]
                k.source != r.source || r.source == SourceKind.INTERNET_ARCHIVE
            }
            if (twinIdx != null) {
                val winner = kept[twinIdx]
                kept[twinIdx] = winner.copy(
                    options = Ranking.mergeOptions(winner.options, r.options),
                    coverUrl = winner.coverUrl ?: r.coverUrl,
                    description = winner.description ?: r.description,
                    year = winner.year ?: r.year,
                )
            } else {
                index.getOrPut(key) { mutableListOf() }.add(kept.size)
                kept += r
            }
        }
        return kept.withIndex()
            .sortedWith(compareByDescending<IndexedValue<BookResult>> { Ranking.score(query, it.value) }.thenBy { it.index })
            .map { it.value }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
    }
}

internal object Ranking {
    private val FORMAT_ORDER = listOf(BookFormat.AZW3, BookFormat.MOBI, BookFormat.EPUB, BookFormat.PDF, BookFormat.TXT)

    fun bestOption(options: List<DownloadOption>): DownloadOption? =
        options.minWithOrNull(compareBy { FORMAT_ORDER.indexOf(it.format) })

    /** Adds formats the winner lacks; the winner's own URL for a format always wins. */
    fun mergeOptions(winner: List<DownloadOption>, other: List<DownloadOption>): List<DownloadOption> {
        val have = winner.map { it.format }.toSet()
        return (winner + other.filter { it.format !in have }.distinctBy { it.format })
            .sortedBy { FORMAT_ORDER.indexOf(it.format) }
    }

    /** Lower is better. Standard Ebooks > Gutenberg > user catalogs > Internet Archive. */
    fun priority(kind: SourceKind): Int = when (kind) {
        SourceKind.STANDARD_EBOOKS -> 0
        SourceKind.GUTENBERG -> 1
        SourceKind.OPDS -> 2
        SourceKind.INTERNET_ARCHIVE -> 3
    }

    fun score(query: String, r: BookResult): Int {
        val q = TextUtil.words(query)
        val qTitle = TextUtil.normTitle(query)
        val title = TextUtil.normTitle(r.title)
        val titleWords = TextUtil.words(r.title).toSet()
        val authorWords = TextUtil.words(r.author).toSet()
        var s = 0
        s += when {
            title == qTitle && title.isNotEmpty() -> 100
            title.startsWith(qTitle) && qTitle.isNotEmpty() -> 70
            q.isNotEmpty() && q.all { it in titleWords } -> 50
            q.any { it in titleWords } -> 15
            else -> 0
        }
        if (q.isNotEmpty() && q.count { it in authorWords } > 0) s += 12
        val best = bestOption(r.options)
        if (best != null && best.format.kindleNative) s += 20
        s += 6 - priority(r.source) * 2
        if (r.needsResolve) s -= 3
        return s
    }
}
