package app.pagedrop.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.BookRepository
import app.pagedrop.data.SendFormatDecision
import app.pagedrop.data.SendFormatSetting
import app.pagedrop.data.SendFormatSettings
import app.pagedrop.data.SendPlan
import app.pagedrop.data.local.database.Book
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.DownloadOption
import app.pagedrop.discover.SearchResults
import app.pagedrop.discover.SourceKind
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the search box and results list show. */
data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val results: SearchResults? = null,
    /** The whole search failed (not just one source). */
    val failure: String? = null,
    val hasSources: Boolean = true,
)

enum class WorkStage { Preparing, Downloading, Converting }

/** Progress of "download -> import -> (convert) -> hand to the send flow". */
sealed interface ActionState {
    data object Idle : ActionState
    data class Working(val stage: WorkStage, val progress: Float? = null) : ActionState
    data class Failed(val message: String) : ActionState
    /** [book] is in the library; when [sendRequested] the UI hands it to the send flow. */
    data class Finished(val book: Book, val sendRequested: Boolean) : ActionState
}

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val finder: BookFinder,
    private val fetcher: BookFetcher,
    private val bookRepository: BookRepository,
    private val recentSearches: RecentSearches,
    private val sendFormatSettings: SendFormatSettings,
) : ViewModel() {

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    val recent: StateFlow<List<String>> = recentSearches.items

    private val _libraryBooks = MutableStateFlow<List<Book>>(emptyList())
    val libraryBooks: StateFlow<List<Book>> = _libraryBooks.asStateFlow()

    private val _selected = MutableStateFlow<BookResult?>(null)
    val selected: StateFlow<BookResult?> = _selected.asStateFlow()

    private val _chosenOption = MutableStateFlow<DownloadOption?>(null)
    val chosenOption: StateFlow<DownloadOption?> = _chosenOption.asStateFlow()

    /** The user's keep/convert pick on this sheet; null follows the Send format setting. */
    private val _keepOverride = MutableStateFlow<Boolean?>(null)
    val keepOverride: StateFlow<Boolean?> = _keepOverride.asStateFlow()

    /** The Send format setting, for the sheet's preselection. */
    val sendFormat: StateFlow<SendFormatSetting> = sendFormatSettings.format

    fun setKeepOriginal(keep: Boolean) {
        if (_action.value !is ActionState.Working) _keepOverride.value = keep
    }

    private val _action = MutableStateFlow<ActionState>(ActionState.Idle)
    val action: StateFlow<ActionState> = _action.asStateFlow()

    private var searchJob: Job? = null
    private var actionJob: Job? = null

    /** Remembered so Retry resumes where the last attempt stopped. */
    private var lastSend = false
    private var importedBook: Book? = null

    init {
        viewModelScope.launch {
            bookRepository.getBooks().catch { }.collect { _libraryBooks.value = it }
        }
        viewModelScope.launch { runCatching { finder.warmUp() } }
    }

    fun onQueryChange(value: String) = _search.update { it.copy(query = value) }

    fun runSearch(query: String = _search.value.query) {
        val q = query.trim()
        searchJob?.cancel()
        if (q.isEmpty()) {
            _search.value = SearchState(hasSources = finder.hasEnabledSources())
            return
        }
        recentSearches.add(q)
        _search.value = SearchState(query = q, searching = true, hasSources = finder.hasEnabledSources())
        searchJob = viewModelScope.launch {
            try {
                val results = finder.search(q)
                _search.update { it.copy(searching = false, results = results, failure = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _search.update { it.copy(searching = false, results = null, failure = friendlyError(e, "search")) }
            }
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        _search.value = SearchState(hasSources = finder.hasEnabledSources())
    }

    fun clearRecent() = recentSearches.clear()

    fun bestFormat(result: BookResult) = finder.bestOption(result)?.format

    fun libraryMatch(result: BookResult): Book? = _libraryBooks.value.firstOrNull { matchesLibraryBook(result, it) }

    // ── Result sheet ──

    fun select(result: BookResult) {
        actionJob?.cancel()
        importedBook = null
        _keepOverride.value = null
        _action.value = ActionState.Idle
        _selected.value = result
        _chosenOption.value = finder.bestOption(result)
        if (result.needsResolve) {
            actionJob = viewModelScope.launch {
                _action.value = ActionState.Working(WorkStage.Preparing)
                try {
                    val resolved = finder.resolve(result)
                    if (_selected.value?.id == result.id) {
                        _selected.value = resolved
                        _chosenOption.value = finder.bestOption(resolved)
                        _action.value = ActionState.Idle
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _action.value = ActionState.Failed(friendlyError(e))
                }
            }
        }
    }

    fun chooseOption(option: DownloadOption) {
        if (_action.value !is ActionState.Working) _chosenOption.value = option
    }

    fun editBrowserMetadata(title: String, author: String) {
        if (_action.value is ActionState.Working || importedBook != null) return
        _selected.update { result ->
            if (result?.source == SourceKind.WEBSITE) result.copy(title = title.take(300), author = author.take(200)) else result
        }
    }

    /** Closes the sheet; a running download is cancelled. */
    fun dismissSelected() {
        actionJob?.cancel()
        actionJob = null
        importedBook = null
        _keepOverride.value = null
        _selected.value = null
        _chosenOption.value = null
        _action.value = ActionState.Idle
    }

    /** Called by the UI once it has handed a finished book to the send flow. */
    fun consumeFinished() = dismissSelected()

    fun addToLibrary() = start(send = false)

    fun sendToKindle() = start(send = true)

    fun retry() {
        val result = _selected.value ?: return
        if (result.needsResolve && _chosenOption.value == null) select(result) else start(lastSend)
    }

    private fun start(send: Boolean) {
        val result = _selected.value ?: return
        if (_action.value is ActionState.Working) return
        lastSend = send
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            var stage = "download"
            try {
                // Already in the library: no download, just (convert and) send.
                // Filename guesses from a website must never select an existing edition automatically.
                var book = importedBook ?: if (result.source == SourceKind.WEBSITE) null else libraryMatch(result)
                if (book == null) {
                    _action.value = ActionState.Working(WorkStage.Preparing)
                    val resolved = if (result.needsResolve && result.options.isEmpty()) finder.resolve(result) else result
                    val option = _chosenOption.value?.takeIf { it in resolved.options }
                        ?: finder.bestOption(resolved)
                        ?: throw IllegalStateException("No downloadable file was found for this book.")
                    _action.value = ActionState.Working(WorkStage.Downloading, null)
                    val file = fetcher.download(resolved, option) { p ->
                        _action.value = ActionState.Working(WorkStage.Downloading, p)
                    }
                    stage = "import"
                    book = bookRepository.importDownloaded(file, resolved.title, resolved.author, resolved.coverUrl)
                    importedBook = book
                }
                // Keep = no conversion at all (no on-phone work, no server, no upload prompt); Convert applies to Add to library too.
                val plan = SendFormatDecision.decide(book.format, sendFormatSettings.format.value, _keepOverride.value)
                if (plan != SendPlan.KeepOriginal) {
                    stage = "convert"
                    _action.value = ActionState.Working(WorkStage.Converting)
                    book = bookRepository.convertImported(book)
                    importedBook = book
                }
                _action.value = ActionState.Finished(book, send)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _action.value = ActionState.Failed(friendlyError(e, stage))
            }
        }
    }
}
