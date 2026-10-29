package app.pagedrop.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.data.local.database.Book
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.CatalogItem
import app.pagedrop.discover.SourceKind
import app.pagedrop.ui.components.HairlineCard
import app.pagedrop.ui.theme.BookSerif

/**
 * Browse one OPDS catalog the user added: folders and books exactly as the catalog lists them, one level at a time.
 * Back goes up one level until the root. Tapping a book opens the usual [ResultSheet].
 */
@Composable
fun CatalogScreen(
    catalogId: String,
    feedUrl: String?,
    onBack: () -> Unit,
    onSendBook: (Book) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel(),
    discover: DiscoverViewModel = hiltViewModel(),
    handoff: SendHandoffViewModel = hiltViewModel(),
) {
    LaunchedEffect(catalogId, feedUrl) { viewModel.start(catalogId, feedUrl) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val library by discover.libraryBooks.collectAsStateWithLifecycle()
    val selected by discover.selected.collectAsStateWithLifecycle()
    val action by discover.action.collectAsStateWithLifecycle()
    val chosen by discover.chosenOption.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    // System back: close the sheet, then the search box, then go up a level, and only then leave.
    BackHandler(enabled = selected != null) { discover.dismissSelected() }
    BackHandler(enabled = selected == null && (state.searchOpen || !state.atRoot)) { viewModel.back() }

    LaunchedEffect(action) {
        val done = action as? ActionState.Finished ?: return@LaunchedEffect
        if (done.sendRequested) {
            discover.consumeFinished()
            handoff.handoff.offer(done.book)
            onSendBook(done.book)
        }
    }

    val level = state.current
    val listStates = remember { HashMap<Int, LazyListState>() }
    listStates.keys.retainAll(state.stack.map { it.id }.toSet())

    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CatalogTopBar(
            title = level?.title ?: state.catalogName,
            searchOpen = state.searchOpen,
            canSearch = state.canSearch,
            onBack = { if (!viewModel.back()) onBack() },
            onRefresh = viewModel::refresh,
            onSearchOpen = viewModel::setSearchOpen,
            onSearch = { keyboard?.hide(); viewModel.search(it) },
        )
        if (level == null || level.status == LevelStatus.Loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
        }
        if (level != null) {
            when {
                level.status == LevelStatus.Failed -> ErrorState(state.catalogName, level.error.orEmpty(), viewModel::refresh)
                level.status == LevelStatus.Ready && level.items.isEmpty() -> NoticeText(
                    (level.request as? CatalogRequest.Search)?.let { "Nothing found for “${it.query}”." } ?: "Nothing here."
                )
                level.status == LevelStatus.Ready -> {
                    val listState = listStates.getOrPut(level.id) { LazyListState() }
                    ItemList(
                        level = level,
                        listState = listState,
                        library = library,
                        onFolder = viewModel::openFolder,
                        onBook = { keyboard?.hide(); discover.select(it) },
                        onLoadMore = viewModel::loadMore,
                    )
                }
            }
        }
    }

    selected?.let { result ->
        ResultSheet(
            result = result,
            action = action,
            chosen = chosen,
            libraryBook = if (result.source == SourceKind.WEBSITE) null else library.firstOrNull { matchesLibraryBook(result, it) },
            onChoose = discover::chooseOption,
            onSend = discover::sendToKindle,
            onAdd = discover::addToLibrary,
            onRetry = discover::retry,
            onEditMetadata = discover::editBrowserMetadata,
            onOpenWebsite = {},
            onDismiss = discover::dismissSelected,
        )
    }
}

@Composable
private fun CatalogTopBar(
    title: String,
    searchOpen: Boolean,
    canSearch: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSearchOpen: (Boolean) -> Unit,
    onSearch: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searchOpen) { if (searchOpen) focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        if (searchOpen) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                singleLine = true,
                shape = MaterialTheme.shapes.small,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookSerif),
                placeholder = { Text("Search this catalog…", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookSerif)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
            )
            IconButton(onClick = { onSearchOpen(false) }) { Icon(Icons.Outlined.Close, "Close search") }
        } else {
            Text(
                title,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (canSearch) IconButton(onClick = { onSearchOpen(true) }) { Icon(Icons.Outlined.Search, "Search this catalog") }
            IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh") }
        }
    }
}

@Composable
private fun ItemList(
    level: CatalogLevel,
    listState: LazyListState,
    library: List<Book>,
    onFolder: (CatalogItem.Folder) -> Unit,
    onBook: (BookResult) -> Unit,
    onLoadMore: () -> Unit,
) {
    // Near the end of the list: fetch the next page (until a failure puts the "Load more" row there instead).
    val nearEnd by remember(listState) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, level.nextUrl, level.items.size, level.moreFailed, level.loadingMore) {
        if (nearEnd && level.nextUrl != null && !level.moreFailed && !level.loadingMore) onLoadMore()
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(level.items, key = { item ->
            when (item) {
                is CatalogItem.Folder -> "f:${item.url}"
                is CatalogItem.Book -> "b:${item.result.id}"
            }
        }) { item ->
            when (item) {
                is CatalogItem.Folder -> FolderRow(item) { onFolder(item) }
                is CatalogItem.Book -> BookRow(
                    result = item.result,
                    inLibrary = library.any { matchesLibraryBook(item.result, it) },
                    onClick = { onBook(item.result) },
                )
            }
        }
        if (level.loadingMore) {
            item(key = "loading-more") { NoticeText("Loading more…") }
        } else if (level.moreFailed && level.nextUrl != null) {
            item(key = "load-more") {
                HairlineCard(Modifier.fillMaxWidth(), onClick = onLoadMore) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Load more", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Couldn’t load the next page. Tap to try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: CatalogItem.Folder, onClick: () -> Unit) {
    HairlineCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            folder.thumbnailUrl?.let { RemoteCover(it, folder.title, Modifier.width(40.dp).height(56.dp)) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(folder.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                folder.summary?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BookRow(result: BookResult, inLibrary: Boolean, onClick: () -> Unit) {
    val usable = result.options.isNotEmpty()
    HairlineCard(Modifier.fillMaxWidth(), onClick = if (usable) onClick else null) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteCover(result.coverUrl, result.title, Modifier.width(56.dp).height(84.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(result.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (result.author.isNotBlank()) {
                    Text(
                        result.author,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                ChipRow {
                    if (usable) result.options.forEach { TagChip(it.format.name) } else TagChip("No supported file")
                    if (inLibrary) TagChip("In your library", emphasized = true)
                }
            }
        }
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun ErrorState(catalogName: String, message: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$catalogName is unavailable", style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
}
