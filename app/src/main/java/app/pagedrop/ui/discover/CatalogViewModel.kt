package app.pagedrop.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.discover.CatalogItem
import app.pagedrop.discover.CatalogPage
import app.pagedrop.discover.CatalogPageKind
import app.pagedrop.discover.CatalogSearch
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a level of the catalog view was reached, so it can be reloaded. */
sealed interface CatalogRequest {
    /** [url] null = the catalog's root feed. */
    data class Browse(val url: String?) : CatalogRequest
    data class Search(val link: CatalogSearch?, val query: String) : CatalogRequest
}

enum class LevelStatus { Loading, Ready, Failed }

/** One feed the user has walked into; the stack of these is the catalog view's "up one level" history. */
data class CatalogLevel(
    val id: Int,
    val request: CatalogRequest,
    val title: String,
    /** True when [title] is the name of the entry the user tapped (a feed's own title doesn't replace it). */
    val titleFixed: Boolean = false,
    val items: List<CatalogItem> = emptyList(),
    val nextUrl: String? = null,
    val search: CatalogSearch? = null,
    val kind: CatalogPageKind = CatalogPageKind.EMPTY,
    val status: LevelStatus = LevelStatus.Loading,
    val error: String? = null,
    val loadingMore: Boolean = false,
    /** Fetching the next page failed: show "Load more" and stop auto-loading. */
    val moreFailed: Boolean = false,
)

data class CatalogState(
    val catalogName: String = "",
    val stack: List<CatalogLevel> = emptyList(),
    val searchOpen: Boolean = false,
) {
    val current: CatalogLevel? get() = stack.lastOrNull()
    val atRoot: Boolean get() = stack.size <= 1

    /** Search link for the current level: its own, else the one the catalog's root feed offers. */
    val searchLink: CatalogSearch? get() = current?.search ?: stack.firstOrNull()?.search
    val canSearch: Boolean get() = searchLink != null
}

private fun CatalogItem.key(): String = when (this) {
    is CatalogItem.Folder -> "f:$url"
    is CatalogItem.Book -> "b:${result.id}"
}

@HiltViewModel
class CatalogViewModel @Inject constructor(
    private val browser: CatalogBrowser,
) : ViewModel() {

    private val _state = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = _state.asStateFlow()

    private var catalogId: String? = null
    private var nextLevelId = 0
    private val jobs = HashMap<Int, Job>()

    /** Opens the catalog at [feedUrl] (null = its root). Only the first call counts, so rotation keeps the stack. */
    fun start(catalogId: String, feedUrl: String?) {
        if (this.catalogId != null) return
        this.catalogId = catalogId
        val name = browser.catalogName(catalogId).orEmpty()
        val root = newLevel(CatalogRequest.Browse(feedUrl), title = name)
        _state.value = CatalogState(catalogName = name, stack = listOf(root))
        fetch(root)
    }

    fun openFolder(folder: CatalogItem.Folder) {
        val level = newLevel(CatalogRequest.Browse(folder.url), title = folder.title, titleFixed = true)
        _state.update { it.copy(stack = it.stack + level, searchOpen = false) }
        fetch(level)
    }

    /** Goes up one level (or closes the search box first). False when there is nothing left to go back to. */
    fun back(): Boolean {
        val s = _state.value
        if (s.searchOpen) {
            _state.value = s.copy(searchOpen = false)
            return true
        }
        if (s.stack.size <= 1) return false
        s.current?.let { jobs.remove(it.id)?.cancel() }
        _state.value = s.copy(stack = s.stack.dropLast(1))
        return true
    }

    fun setSearchOpen(open: Boolean) = _state.update { if (open && !it.canSearch) it else it.copy(searchOpen = open) }

    /** Searches the catalog; the answer (books or a menu of choices) becomes a new level. Nothing is followed. */
    fun search(query: String) {
        val q = query.trim()
        val s = _state.value
        if (q.isEmpty() || !s.canSearch) return
        val base = if (s.current?.request is CatalogRequest.Search) s.stack.dropLast(1) else s.stack
        if (base.size != s.stack.size) jobs.remove(s.current!!.id)?.cancel()
        val level = newLevel(CatalogRequest.Search(s.searchLink, q), title = "Search: “$q”")
        _state.value = s.copy(stack = base + level, searchOpen = false)
        fetch(level)
    }

    /** Reloads the current level from its first page. */
    fun refresh() {
        val level = _state.value.current ?: return
        val fresh = level.copy(
            items = emptyList(), nextUrl = null, status = LevelStatus.Loading, error = null,
            loadingMore = false, moreFailed = false,
        )
        replace(fresh)
        fetch(fresh)
    }

    /** Fetches the next page of the current level (called near the end of the list, and by the "Load more" row). */
    fun loadMore() {
        val level = _state.value.current ?: return
        val url = level.nextUrl ?: return
        if (level.status != LevelStatus.Ready || level.loadingMore) return
        update(level.id) { it.copy(loadingMore = true, moreFailed = false) }
        jobs[level.id] = viewModelScope.launch {
            try {
                val page = browser.open(catalogId!!, url)
                update(level.id) { cur ->
                    val seen = cur.items.mapTo(HashSet()) { it.key() }
                    val added = page.items.filter { seen.add(it.key()) }
                    cur.copy(
                        items = cur.items + added,
                        // A server that repeats itself must not keep us paging forever.
                        nextUrl = page.nextUrl?.takeIf { added.isNotEmpty() && it != url },
                        loadingMore = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(level.id) { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }

    private fun newLevel(request: CatalogRequest, title: String, titleFixed: Boolean = false) =
        CatalogLevel(id = nextLevelId++, request = request, title = title, titleFixed = titleFixed)

    private fun fetch(level: CatalogLevel) {
        jobs.remove(level.id)?.cancel()
        jobs[level.id] = viewModelScope.launch {
            try {
                val id = catalogId!!
                val page: CatalogPage = when (val r = level.request) {
                    is CatalogRequest.Browse -> browser.open(id, r.url)
                    is CatalogRequest.Search -> browser.search(id, r.link, r.query)
                }
                update(level.id) {
                    it.copy(
                        title = if (it.titleFixed) it.title else page.title ?: it.title,
                        items = page.items.distinctBy { item -> item.key() },
                        nextUrl = page.nextUrl,
                        search = page.search,
                        kind = page.kind,
                        status = LevelStatus.Ready,
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update(level.id) {
                    it.copy(status = LevelStatus.Failed, error = e.message?.takeIf { m -> m.isNotBlank() } ?: "Something went wrong")
                }
            }
        }
    }

    private fun replace(level: CatalogLevel) = update(level.id) { level }

    /** Applies [change] to the level with [id] if it is still on the stack (it may have been popped meanwhile). */
    private fun update(id: Int, change: (CatalogLevel) -> CatalogLevel) {
        _state.update { s ->
            if (s.stack.none { it.id == id }) s
            else s.copy(stack = s.stack.map { if (it.id == id) change(it) else it })
        }
    }
}
