package app.pagedrop.ui.discover

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.data.local.database.Book
import app.pagedrop.discover.BookDownloader
import app.pagedrop.discover.BookFormat
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.BookSearchEngine
import app.pagedrop.discover.DownloadOption
import app.pagedrop.discover.SearchResults
import app.pagedrop.discover.SourceSettings
import app.pagedrop.discover.TextUtil
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The slice of the discover backend the Find screen needs. The real implementations are thin
 * adapters over the engine / downloader / settings; tests use fakes.
 */
interface BookFinder {
    suspend fun search(query: String): SearchResults
    suspend fun resolve(result: BookResult): BookResult
    fun bestOption(result: BookResult): DownloadOption?
    fun hasEnabledSources(): Boolean
}

interface BookFetcher {
    suspend fun download(result: BookResult, option: DownloadOption, onProgress: (Float?) -> Unit): File
}

class EngineBookFinder @Inject constructor(
    private val engine: BookSearchEngine,
    private val settings: SourceSettings,
) : BookFinder {
    override suspend fun search(query: String) = engine.search(query)
    override suspend fun resolve(result: BookResult) = engine.resolve(result)
    override fun bestOption(result: BookResult) = engine.bestOption(result)
    override fun hasEnabledSources(): Boolean =
        settings.enabledBuiltIns.value.isNotEmpty() || settings.catalogs.value.any { it.enabled }
}

class DownloaderBookFetcher @Inject constructor(private val downloader: BookDownloader) : BookFetcher {
    override suspend fun download(result: BookResult, option: DownloadOption, onProgress: (Float?) -> Unit) =
        downloader.download(result, option, onProgress)
}

/** Last few searches, newest first. */
interface RecentSearches {
    val items: StateFlow<List<String>>
    fun add(query: String)
    fun clear()
}

@Singleton
class PrefsRecentSearches @Inject constructor(@ApplicationContext context: Context) : RecentSearches {
    private val prefs: SharedPreferences = context.getSharedPreferences("recent_searches", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    override val items: StateFlow<List<String>> = _items.asStateFlow()

    private fun load(): List<String> =
        prefs.getString(KEY, "").orEmpty().split('\n').filter { it.isNotBlank() }.take(MAX_RECENT)

    override fun add(query: String) {
        _items.value = withRecent(_items.value, query)
        prefs.edit().putString(KEY, _items.value.joinToString("\n")).apply()
    }

    override fun clear() {
        _items.value = emptyList()
        prefs.edit().remove(KEY).apply()
    }

    private companion object { const val KEY = "items" }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoverUiModule {
    @Binds abstract fun finder(impl: EngineBookFinder): BookFinder
    @Binds abstract fun fetcher(impl: DownloaderBookFetcher): BookFetcher
    @Binds abstract fun recent(impl: PrefsRecentSearches): RecentSearches
}

internal const val MAX_RECENT = 8

/** [query] moved to the front, case-insensitive duplicates dropped, capped at [MAX_RECENT]. */
internal fun withRecent(current: List<String>, query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return current
    return (listOf(q) + current.filterNot { it.equals(q, ignoreCase = true) }).take(MAX_RECENT)
}

internal fun normalizeForMatch(text: String): String =
    TextUtil.words(text).joinToString(" ")

/** Same book when titles match and the authors match (or one side doesn't know its author). */
internal fun matchesLibraryBook(result: BookResult, book: Book): Boolean {
    if (normalizeForMatch(result.title) != normalizeForMatch(book.title)) return false
    val a = normalizeForMatch(result.author)
    val b = normalizeForMatch(book.author)
    val unknown = setOf("", "unknown author", "unknown")
    return a in unknown || b in unknown || a == b
}

/** Text for the "will it work on my Kindle" chip. */
internal fun formatChipLabel(format: BookFormat?): String? = when (format) {
    null -> null
    BookFormat.AZW3, BookFormat.MOBI -> "Kindle-ready"
    BookFormat.EPUB -> "EPUB · will convert"
    BookFormat.PDF -> "PDF"
    BookFormat.TXT -> "Plain text"
}

/** Plain-language reason a download/import/convert step failed. */
internal fun friendlyError(error: Throwable, stage: String = "download"): String = when {
    error is UnknownHostException || error is SocketTimeoutException ->
        "Couldn’t reach the internet. Check your connection and try again."
    error is IOException && stage == "download" ->
        error.message?.takeIf { it.length in 1..120 } ?: "The download was interrupted. Try again."
    stage == "convert" ->
        "Couldn’t convert this EPUB for your Kindle. The book is saved in your library; you can try Convert from its menu."
    else -> error.message?.takeIf { it.length in 1..120 } ?: "Something went wrong. Please try again."
}
