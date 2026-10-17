package app.pagedrop.ui.book

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pagedrop.data.local.database.Book
import app.pagedrop.ui.components.BookCover
import app.pagedrop.ui.components.HairlineCard
import app.pagedrop.ui.components.OverBackgroundSurfaceAlpha
import app.pagedrop.ui.components.StatusLine

/** Per-book flags the list and grid need; keeps the lazy layouts free of lookups. */
internal data class BookItemState(
    val book: Book,
    val transfer: BookTransfer,
    val selected: Boolean,
)

@Composable
internal fun BookList(
    items: List<BookItemState>,
    selectionMode: Boolean,
    onClick: (Book) -> Unit,
    onLongClick: (Book) -> Unit,
    header: @Composable () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { header() }
        items(items = items, key = { it.book.uid }) { item ->
            BookListItem(
                state = item,
                selectionMode = selectionMode,
                onClick = { onClick(item.book) },
                onLongClick = { onLongClick(item.book) },
            )
        }
    }
}

@Composable
internal fun BookGrid(
    items: List<BookItemState>,
    selectionMode: Boolean,
    onClick: (Book) -> Unit,
    onLongClick: (Book) -> Unit,
    header: @Composable () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = modifier,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header", span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) { header() }
        items(items = items, key = { it.book.uid }) { item ->
            BookTile(
                state = item,
                selectionMode = selectionMode,
                onClick = { onClick(item.book) },
                onLongClick = { onLongClick(item.book) },
            )
        }
    }
}

/** List row: cover thumbnail, serif title, italic author, format chip, size and status. */
@Composable
internal fun BookListItem(
    state: BookItemState,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = state.book
    val context = LocalContext.current
    val status = bookStatus(book, state.transfer)

    HairlineCard(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        onLongClick = onLongClick,
        selected = state.selected,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selectionMode) {
                Checkbox(checked = state.selected, onCheckedChange = null)
            }
            BookCover(
                coverPath = book.coverPath,
                title = book.title,
                modifier = Modifier.width(56.dp).aspectRatio(2f / 3f),
                showTitleOnPlaceholder = false,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FormatChip(book.format)
                    Text(
                        text = Formatter.formatShortFileSize(context, book.fileSize),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusLine(status.text, tone = status.tone)
            }
        }
        if (state.transfer == BookTransfer.Sending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
    }
}

/** Grid tile like the Kindle home screen: cover with the title underneath. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BookTile(
    state: BookItemState,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = state.book
    val status = bookStatus(book, state.transfer)
    Column(
        modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            BookCover(
                coverPath = book.coverPath,
                title = book.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3f)
                    .then(
                        if (state.selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, MaterialTheme.shapes.extraSmall)
                        else Modifier
                    ),
            )
            if (selectionMode) {
                Checkbox(
                    checked = state.selected,
                    onCheckedChange = null,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                )
            }
        }
        if (state.transfer == BookTransfer.Sending) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
        }
        Text(
            text = book.title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        StatusLine(status.text, tone = status.tone)
    }
}

@Composable
private fun FormatChip(format: String) {
    Box(
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = format.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
