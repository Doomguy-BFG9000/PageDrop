package app.pagedrop.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
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
                        val found = withTimeout(sourceTimeoutMs) { runInterruptible(Dispatchers.IO) { source.search(q) } }
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
        return SearchResults(query, merged, outcomes.mapNotNull { it.error },
            BookWebSearch.links(q, outcomes.filter { it.error != null }.map { it.source.kind }.toSet(), settings.showShadowLibraries.value))
    }

    /** Fills [BookResult.options] when [BookResult.needsResolve] (Internet Archive). Throws [IOException] on network failure. */
    suspend fun resolve(result: BookResult): BookResult {
        if (!result.needsResolve) return result
        return try {
            withTimeout(sourceTimeoutMs) {
                runInterruptible(Dispatchers.IO) {
                    when (result.source) {
                        SourceKind.INTERNET_ARCHIVE, SourceKind.DIGITAL_LIBRARY_INDIA, SourceKind.OPEN_LIBRARY ->
                            InternetArchiveSource(http.withTimeout(sourceTimeoutMs)).resolve(result)
                        SourceKind.FADED_PAGE -> FadedPageSource(http.withTimeout(sourceTimeoutMs)).resolve(result)
                        SourceKind.GUTENBERG -> GutenbergSource(http.withTimeout(sourceTimeoutMs)).resolve(result)
                        else -> result.copy(needsResolve = false)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw SourceUnavailableException("${result.sourceName} took too long to check formats. Try again.", cause = e)
        } catch (e: IOException) {
            throw describe(e, result.sourceName)
        }
    }

    /** AZW3 > MOBI > EPUB > PDF > TXT. (Stock Kindles read AZW3/MOBI/PDF/TXT; EPUB is converted on import.) */
    fun bestOption(result: BookResult): DownloadOption? = Ranking.bestOption(result.options)

    private fun buildSources(): List<BookSource> {
        val enabled = settings.enabledBuiltIns.value
        val sourceHttp = http.withTimeout(sourceTimeoutMs)
        val list = mutableListOf<BookSource>()
        if (SourceKind.STANDARD_EBOOKS in enabled) list += StandardEbooksSource(sourceHttp)
        if (SourceKind.GUTENBERG in enabled) list += GutenbergSource(sourceHttp)
        if (SourceKind.INTERNET_ARCHIVE in enabled) list += InternetArchiveSource(sourceHttp)
        if (SourceKind.DIGITAL_LIBRARY_INDIA in enabled) list += InternetArchiveSource(sourceHttp, SourceKind.DIGITAL_LIBRARY_INDIA)
        if (SourceKind.OPEN_LIBRARY in enabled) list += OpenLibrarySource(sourceHttp)
        if (SourceKind.HINDI_WIKISOURCE in enabled) list += WikisourceSource(sourceHttp)
        if (SourceKind.HINDWI in enabled) list += RekhtaSource(sourceHttp, SourceKind.HINDWI)
        if (SourceKind.REKHTA in enabled) list += RekhtaSource(sourceHttp, SourceKind.REKHTA)
        if (SourceKind.ENGLISH_WIKISOURCE in enabled) list += WikisourceSource(sourceHttp, SourceKind.ENGLISH_WIKISOURCE)
        if (SourceKind.FADED_PAGE in enabled) list += FadedPageSource(sourceHttp)
        if (SourceKind.MANYBOOKS in enabled) list += ManyBooksSource(sourceHttp)
        settings.catalogs.value.filter { it.enabled }.forEach { c ->
            list += OpdsSource(c, settings.credentialsFor(c.id), sourceHttp, opdsTemplates)
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
        val byPriority = all.sortedBy { Ranking.priority(it.source) }.distinctBy {
            it.archiveIdentifier?.let { id -> "archive:$id" } ?: it.id
        }
        val kept = mutableListOf<BookResult>()
        val index = HashMap<String, MutableList<Int>>()
        for (r in byPriority) {
            val key = TextUtil.dedupeKey(r.title, r.author) + "|" + TextUtil.shortLanguage(r.language).orEmpty()
            val twinIdx = if (r.author.isBlank() || r.needsResolve || r.options.isEmpty() || key.startsWith("|")) null else index[key]?.firstOrNull { i ->
                val k = kept[i]
                k.source != r.source && !k.needsResolve && k.options.isNotEmpty()
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

    /** Lower is better. Downloadable editions rank ahead of web readers for otherwise equal matches. */
    fun priority(kind: SourceKind): Int = when (kind) {
        SourceKind.STANDARD_EBOOKS -> 0
        SourceKind.GUTENBERG -> 1
        SourceKind.OPDS -> 2
        SourceKind.WEBSITE -> 5
        SourceKind.INTERNET_ARCHIVE -> 3
        SourceKind.DIGITAL_LIBRARY_INDIA -> 3
        SourceKind.OPEN_LIBRARY -> 4
        SourceKind.HINDI_WIKISOURCE -> 5
        SourceKind.HINDWI -> 5
        SourceKind.REKHTA -> 5
        SourceKind.ENGLISH_WIKISOURCE -> 5
        SourceKind.FADED_PAGE -> 2
        SourceKind.MANYBOOKS -> 5
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
