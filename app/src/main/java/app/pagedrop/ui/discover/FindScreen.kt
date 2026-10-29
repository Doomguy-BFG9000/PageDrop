package app.pagedrop.ui.discover

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.data.SendFormatDecision
import app.pagedrop.data.SendPlan
import app.pagedrop.data.local.database.Book
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.SourceError
import app.pagedrop.discover.SourceKind
import app.pagedrop.ui.components.HairlineCard
import app.pagedrop.ui.theme.BookSerif
import coil.compose.SubcomposeAsyncImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import app.pagedrop.ui.components.CoverPlaceholder
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Full-screen "Find a book": query field, results, recent searches. Picking a result opens
 * [ResultSheet]; a finished "Send to Kindle" is handed to [onSendBook] (the library's send flow).
 */
@Composable
fun FindScreen(
    onBack: () -> Unit,
    onSendBook: (Book) -> Unit,
    onOpenSources: () -> Unit,
    onOpenCatalog: (catalogId: String, feedUrl: String?) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel(),
    sourcesViewModel: app.pagedrop.ui.settings.BookSourcesViewModel = hiltViewModel(),
) {
    val showShadow by sourcesViewModel.showShadowLibraries.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val catalogs by viewModel.catalogs.collectAsStateWithLifecycle()
    val library by viewModel.libraryBooks.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val action by viewModel.action.collectAsStateWithLifecycle()
    val chosen by viewModel.chosenOption.collectAsStateWithLifecycle()
    val keepOverride by viewModel.keepOverride.collectAsStateWithLifecycle()
    val sendFormat by viewModel.sendFormat.collectAsStateWithLifecycle()

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var browserName by rememberSaveable { mutableStateOf<String?>(null) }
    var browserUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var browserInstance by rememberSaveable { mutableIntStateOf(0) }
    fun openSource(name: String, url: String) {
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.dismissSelected()
        browserName = name
        browserUrl = url
        browserInstance++
    }
    LaunchedEffect(Unit) { viewModel.warmUp() }
    LaunchedEffect(Unit) { if (search.results == null && browserUrl == null) focus.requestFocus() }

    BackHandler { if (selected != null) viewModel.dismissSelected() else onBack() }

    // A finished "Send to Kindle" leaves Find and lets the library show the transfer.
    LaunchedEffect(action) {
        val done = action as? ActionState.Finished ?: return@LaunchedEffect
        if (done.sendRequested) {
            viewModel.consumeFinished()
            onSendBook(done.book)
        }
    }

    fun submit(query: String = search.query) {
        keyboard?.hide()
        focusManager.clearFocus()
        viewModel.runSearch(query)
    }

    Box(modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                OutlinedTextField(
                    value = search.query,
                    onValueChange = viewModel::onQueryChange,
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookSerif),
                    placeholder = {
                        Text("Search for a book…", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookSerif))
                    },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    trailingIcon = {
                        if (search.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.clearQuery(); focus.requestFocus() }) {
                                Icon(Icons.Outlined.Close, "Clear")
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { submit() }),
                )
            }
            if (search.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp).height(2.dp))
            } else {
                Spacer(Modifier.height(2.dp))
            }

            val results = search.results
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (results != null && results.errors.isNotEmpty()) {
                    item { SourceErrorChips(results.errors) }
                }
                if (results != null && results.catalogMenus.isNotEmpty()) {
                    items(results.catalogMenus, key = { "menu:${it.catalogId}" }) { menu ->
                        CatalogMenuRow(menu) { onOpenCatalog(menu.catalogId, menu.feedUrl) }
                    }
                }
                when {
                    results != null && results.results.isNotEmpty() -> items(results.results, key = { it.id }) { r ->
                        ResultRow(
                            result = r,
                            bestLabel = formatChipLabel(viewModel.bestFormat(r), sendFormat),
                            inLibrary = library.any { matchesLibraryBook(r, it) },
                            onClick = { keyboard?.hide(); viewModel.select(r) },
                        )
                    }
                    results != null && results.catalogMenus.isNotEmpty() -> Unit
                    results != null && !search.searching -> item {
                        NoticeText(
                            if (results.errors.isNotEmpty()) "Nothing found, and some sources couldn’t be reached."
                            else "No books found for “${results.query}”. Try the title and author, or fewer words."
                        )
                    }
                    search.failure != null -> item { NoticeText(search.failure!!) }
                    !search.searching -> item {
                        IdleContent(
                            recent = recent,
                            catalogs = catalogs,
                            onOpenCatalog = { onOpenCatalog(it, null) },
                            hasSources = search.hasSources,
                            onRecent = { viewModel.onQueryChange(it); submit(it) },
                            onClearRecent = viewModel::clearRecent,
                            onOpenSources = onOpenSources,
                        )
                    }
                }
                val websiteSearches = results?.let {
                    app.pagedrop.discover.BookWebSearch.visibleLinks(it.websiteSearches, showShadow)
                }.orEmpty()
                if (results != null && !search.searching && websiteSearches.isNotEmpty()) {
                    item {
                        Text("SEARCH MORE LIBRARIES", style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(websiteSearches, key = { it.sourceName }) { link ->
                        HairlineCard(Modifier.fillMaxWidth(), onClick = { openSource(link.sourceName, link.url) }) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Search ${link.sourceName}", style = MaterialTheme.typography.titleMedium)
                                Text(link.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }

        browserUrl?.let { url ->
            key(browserInstance) {
                SourceBrowserScreen(
                    SourceBrowserTarget(browserName.orEmpty(), url),
                    onClose = { browserUrl = null; browserName = null },
                    onDownload = viewModel::select,
                )
            }
        }

        selected?.let { result ->
            ResultSheet(
                result = result,
                action = action,
                chosen = chosen,
                libraryBook = if (result.source == SourceKind.WEBSITE) null else library.firstOrNull { matchesLibraryBook(result, it) },
                keepOriginal = SendFormatDecision.decide(
                    library.firstOrNull { result.source != SourceKind.WEBSITE && matchesLibraryBook(result, it) }?.format
                        ?: chosen?.format?.name.orEmpty(),
                    sendFormat,
                    keepOverride,
                ) == SendPlan.KeepOriginal,
                onKeepOriginalChange = viewModel::setKeepOriginal,
                onChoose = viewModel::chooseOption,
                onSend = viewModel::sendToKindle,
                onAdd = viewModel::addToLibrary,
                onRetry = viewModel::retry,
                onEditMetadata = viewModel::editBrowserMetadata,
                onOpenWebsite = { result.websiteUrl?.let { openSource(result.sourceName, it) } },
                onDismiss = viewModel::dismissSelected,
            )
        }
    }
}

@Composable
private fun NoticeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
    )
}

@Composable
private fun SourceErrorChips(errors: List<SourceError>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        errors.forEach { e ->
            TagChip(if (e.offline) "${e.sourceName} unavailable (offline)" else "${e.sourceName} unavailable")
            Text(e.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun IdleContent(
    recent: List<String>,
    catalogs: List<app.pagedrop.discover.OpdsCatalog>,
    onOpenCatalog: (String) -> Unit,
    hasSources: Boolean,
    onRecent: (String) -> Unit,
    onClearRecent: () -> Unit,
    onOpenSources: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!hasSources) {
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("No book sources are turned on", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Turn at least one on to search.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onOpenSources) { Text("Book sources") }
                }
            }
        }
        if (catalogs.isNotEmpty()) {
            Text(
                "YOUR CATALOGS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            catalogs.forEach { c ->
                HairlineCard(Modifier.fillMaxWidth(), onClick = { onOpenCatalog(c.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("Browse this catalog", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "RECENT SEARCHES",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onClearRecent) { Text("Clear") }
            }
            recent.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clickableRow { onRecent(q) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(q, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookSerif))
                }
            }
        }
        HairlineCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Finding free books", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Search by title, author, or both: “Pride and Prejudice”, “Jane Austen”.\n" +
                        "Try Hindi titles too: “गोदान”, “प्रेमचंद”. Try English spellings like “Godaan” as well.\n" +
                        "Search includes Gutenberg, Standard Ebooks, Internet Archive, Digital Library of India, " +
                        "Open Library, Faded Page, ManyBooks, English and Hindi Wikisource, Hindwi and Rekhta, plus your catalogs. " +
                        "Some books open in their source’s web reader. More library searches appear below the results.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "<Catalog>: N search options": the catalog answered with a menu, so the user picks where to look. */
internal fun catalogMenuTitle(menu: app.pagedrop.discover.CatalogMenu): String =
    "${menu.catalogName}: ${menu.optionCount} search option${if (menu.optionCount == 1) "" else "s"}"

@Composable
private fun CatalogMenuRow(menu: app.pagedrop.discover.CatalogMenu, onClick: () -> Unit) {
    HairlineCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(catalogMenuTitle(menu), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    menu.optionTitles.take(3).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

@Composable
private fun ResultRow(result: BookResult, bestLabel: String?, inLibrary: Boolean, onClick: () -> Unit) {
    HairlineCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RemoteCover(result.coverUrl, result.title, Modifier.width(56.dp).height(84.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    result.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.author.isNotBlank()) {
                    Text(
                        result.author,
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                result.year?.let {
                    Text(it.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                ChipRow {
                    TagChip(result.sourceName)
                    bestLabel?.let { TagChip(it, emphasized = it == "Kindle-ready") }
                    if (result.options.isEmpty()) TagChip(if (result.needsResolve) "Check formats" else "Web reader")
                    if (inLibrary) TagChip("In your library", emphasized = true)
                }
            }
        }
    }
}

/** Chips wrap onto a second line instead of squeezing the title. */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun ChipRow(content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

/** Small flat label: hairline outline, ink fill when [emphasized]. */
@Composable
internal fun TagChip(text: String, emphasized: Boolean = false) {
    val ink = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .then(
                if (emphasized) Modifier.background(ink, MaterialTheme.shapes.extraSmall)
                else Modifier.border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), MaterialTheme.shapes.extraSmall)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (emphasized) MaterialTheme.colorScheme.surface else ink,
            maxLines = 1,
        )
    }
}

@Composable
internal fun RemoteCover(url: String?, title: String, modifier: Modifier = Modifier) {
    val frame = modifier
        .clip(MaterialTheme.shapes.extraSmall)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
    if (url.isNullOrBlank()) {
        CoverPlaceholder(title, frame, showTitle = false)
    } else {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            modifier = frame,
            contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) },
            error = { CoverPlaceholder(title, Modifier.fillMaxSize(), showTitle = false) },
        )
    }
}
