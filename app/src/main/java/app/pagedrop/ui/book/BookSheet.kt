package app.pagedrop.ui.book

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pagedrop.converter.BookConverter
import app.pagedrop.data.local.database.Book
import app.pagedrop.ui.components.BookCover
import app.pagedrop.ui.components.StatusLine

/**
 * Opens when a book is tapped: big cover, details, a primary "Send to Kindle" and the
 * secondary actions (edit details & cover, convert format, delete).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookSheet(
    book: Book,
    transfer: BookTransfer,
    deleteBlocked: Boolean = false,
    onSend: () -> Unit,
    onEdit: () -> Unit,
    onConvert: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val status = bookStatus(book, transfer)
    val sending = transfer == BookTransfer.Sending
    val canConvert = BookConverter.canConvert(book.format)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BookCover(
                    coverPath = book.coverPath,
                    title = book.title,
                    modifier = Modifier.width(128.dp).aspectRatio(2f / 3f),
                )
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${book.format.uppercase()} · ${Formatter.formatShortFileSize(context, book.fileSize)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    StatusLine(status.text, tone = status.tone)
                }
            }

            if (sending) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            Button(
                onClick = onSend,
                enabled = !sending,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(
                    when {
                        sending -> "Sending…"
                        book.lastTransferred != null -> "Send again"
                        else -> "Send to Kindle"
                    }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            Column {
                SheetAction("Edit details & cover", Icons.Outlined.Edit, onEdit)
                if (canConvert) SheetAction("Convert format", Icons.Outlined.Sync, onConvert)
                SheetAction("Delete", Icons.Outlined.Delete, onDelete, destructive = true, enabled = !deleteBlocked)
                if (deleteBlocked) {
                    Text(
                        text = "Can't delete while this book is being sent.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Text(label, color = color, modifier = Modifier.padding(start = 16.dp))
        }
    }
}
