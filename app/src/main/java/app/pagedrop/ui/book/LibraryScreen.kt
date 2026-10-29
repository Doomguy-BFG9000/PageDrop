package app.pagedrop.ui.book

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.pagedrop.R
import app.pagedrop.data.local.database.Book
import app.pagedrop.ui.components.OverBackgroundSurfaceAlpha

/** Everything the library screen shows, as plain values and callbacks (preview friendly). */
internal data class LibraryActions(
    val onAddBook: () -> Unit,
    val onFindBook: () -> Unit,
    val onOpenArticle: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onStatusClick: () -> Unit,
    val onSetUp: () -> Unit,
    val onFilterChange: (LibraryFilter) -> Unit,
    val onViewModeChange: (LibraryViewMode) -> Unit,
    val onBookClick: (Book) -> Unit,
    val onBookLongClick: (Book) -> Unit,
    val onClearSelection: () -> Unit,
    val onSelectAll: () -> Unit,
    val onSendSelected: () -> Unit,
    val onDeleteSelected: () -> Unit,
    val onFindCovers: () -> Unit = {},
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryScreenContent(
    uiState: LibraryUiState,
    kindleStatus: KindleStatus,
    filter: LibraryFilter,
    viewMode: LibraryViewMode,
    selectedIds: Set<Int>,
    sendingIds: Set<Int>,
    failedIds: Set<Int>,
    isConverting: Boolean,
    isSending: Boolean,
    transferProgress: BookViewModel.TransferState.Progress?,
    snackbarHostState: SnackbarHostState,
    actions: LibraryActions,
    modifier: Modifier = Modifier,
    missingCovers: Int = 0,
    findingCovers: Boolean = false,
) {
    val selectionMode = selectedIds.isNotEmpty()
    val books = (uiState as? LibraryUiState.Success)?.data.orEmpty()
    val visibleCount = books.count(filter::matches)
    val isEmptyLibrary = uiState is LibraryUiState.Success && books.isEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        // Background poster: full width at its own proportions, pinned to the bottom of the screen.
        // Printed in two tones so its white parts are exactly the screen colour (no "sticker" patches):
        // white -> background, black -> ink. Dark mode prints black on the dark background.
        val background = MaterialTheme.colorScheme.background
        // Light mode prints at about a third strength so book titles stay readable over it.
        val ink = if (background.luminance() < 0.5f) Color.Black else lerp(background, MaterialTheme.colorScheme.onBackground, 0.32f)
        Image(
            painter = painterResource(id = R.drawable.bezos_bg),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
            contentScale = ContentScale.FillWidth,
            alignment = Alignment.BottomCenter,
            colorFilter = remember(background, ink) { posterDuotone(paper = background, ink = ink) },
        )

        // Pinned bar: transparent at rest, solid with a hairline once the list or grid scrolls under it.
        val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            // The shell applies system-bar and bottom-navigation padding to this screen.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            containerColor = Color.Transparent,
            topBar = {
                if (selectionMode) {
                    SelectionTopBar(
                        selectedCount = selectedIds.size,
                        totalCount = visibleCount,
                        onClear = actions.onClearSelection,
                        onSelectAll = actions.onSelectAll,
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    LibraryTopBar(onOpenSettings = actions.onOpenSettings, scrollBehavior = scrollBehavior)
                }
            },
            bottomBar = {
                if (selectionMode) {
                    SelectionBar(
                        selectedCount = selectedIds.size,
                        sending = isSending && sendingIds.containsAll(selectedIds),
                        deleteBlocked = sendingIds.any { it in selectedIds },
                        onSend = actions.onSendSelected,
                        onDelete = actions.onDeleteSelected,
                    )
                }
            },
            floatingActionButton = {
                if (!selectionMode && !isEmptyLibrary && uiState is LibraryUiState.Success) {
                    AddFab(onAddBook = actions.onAddBook, onOpenArticle = actions.onOpenArticle)
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            when (uiState) {
                is LibraryUiState.Loading -> Box(
                    Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onSurface)
                }

                is LibraryUiState.Error -> Box(
                    Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Something went wrong.\n${uiState.throwable.localizedMessage}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                is LibraryUiState.Success -> {
                    if (isEmptyLibrary) {
                        Column(Modifier.fillMaxSize().padding(innerPadding)) {
                            LibraryHeader(
                                kindleStatus = kindleStatus,
                                filter = filter,
                                viewMode = viewMode,
                                isConverting = isConverting,
                                transferProgress = transferProgress,
                                onStatusClick = actions.onStatusClick,
                                onSetUp = actions.onSetUp,
                                onFindBook = actions.onFindBook,
                                onFilterChange = actions.onFilterChange,
                                onViewModeChange = actions.onViewModeChange,
                                modifier = Modifier.padding(horizontal = 16.dp),
                                showControls = false,
                            )
                            EmptyLibraryState(
                                onAddBook = actions.onAddBook,
                                onFindBook = actions.onFindBook,
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                            )
                        }
                    } else {
                        val visible = books.filter(filter::matches).map { book ->
                            BookItemState(
                                book = book,
                                transfer = when (book.uid) {
                                    in sendingIds -> BookTransfer.Sending
                                    in failedIds -> BookTransfer.Failed
                                    else -> BookTransfer.Idle
                                },
                                selected = book.uid in selectedIds,
                            )
                        }
                        val header: @Composable () -> Unit = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                LibraryHeader(
                                    kindleStatus = kindleStatus,
                                    filter = filter,
                                    viewMode = viewMode,
                                    isConverting = isConverting,
                                    transferProgress = transferProgress,
                                    onStatusClick = actions.onStatusClick,
                                    onSetUp = actions.onSetUp,
                                onFindBook = actions.onFindBook,
                                    onFilterChange = actions.onFilterChange,
                                    onViewModeChange = actions.onViewModeChange,
                                    missingCovers = missingCovers,
                                    findingCovers = findingCovers,
                                    onFindCovers = actions.onFindCovers,
                                )
                                if (visible.isEmpty()) {
                                    Text(
                                        text = "No books match this filter",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(
                                                MaterialTheme.colorScheme.surface.copy(alpha = OverBackgroundSurfaceAlpha),
                                                MaterialTheme.shapes.small,
                                            )
                                            .padding(16.dp),
                                    )
                                }
                            }
                        }
                        val padding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = innerPadding.calculateTopPadding() + 4.dp,
                            bottom = innerPadding.calculateBottomPadding() + 120.dp,
                        )
                        when (viewMode) {
                            LibraryViewMode.List -> BookList(
                                items = visible,
                                selectionMode = selectionMode,
                                onClick = actions.onBookClick,
                                onLongClick = actions.onBookLongClick,
                                header = header,
                                contentPadding = padding,
                                modifier = Modifier.fillMaxSize(),
                            )
                            LibraryViewMode.Grid -> BookGrid(
                                items = visible,
                                selectionMode = selectionMode,
                                onClick = actions.onBookClick,
                                onLongClick = actions.onBookLongClick,
                                header = header,
                                contentPadding = padding,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Secondary "Add file" button with a small labelled "Send article from URL" action above it. */
@Composable
private fun AddFab(onAddBook: () -> Unit, onOpenArticle: () -> Unit) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AssistChip(
            onClick = onOpenArticle,
            label = { Text("Send article from URL") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(18.dp)) },
            shape = MaterialTheme.shapes.extraSmall,
            colors = AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        )
        OutlinedButton(
            onClick = onAddBook,
            shape = MaterialTheme.shapes.small,
            colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add file")
        }
    }
}

/**
 * Maps the poster's greyscale onto two colours: luminance 1 (white) becomes [paper], 0 (black) becomes [ink],
 * and everything in between blends linearly. Alpha is left as it is.
 */
internal fun posterDuotone(paper: Color, ink: Color): ColorFilter =
    ColorFilter.colorMatrix(posterDuotoneMatrix(paper, ink))

internal fun posterDuotoneMatrix(paper: Color, ink: Color): ColorMatrix {
    fun row(inkC: Float, paperC: Float): FloatArray {
        val k = paperC - inkC
        return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, inkC * 255f)
    }
    return ColorMatrix(
        row(ink.red, paper.red) + row(ink.green, paper.green) + row(ink.blue, paper.blue) +
            floatArrayOf(0f, 0f, 0f, 1f, 0f)
    )
}
