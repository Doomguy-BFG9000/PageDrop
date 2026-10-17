package app.pagedrop.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.data.local.database.Book
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.DownloadOption

/** Plain-language progress line for the current step. */
internal fun progressLabel(action: ActionState.Working): String = when (action.stage) {
    WorkStage.Preparing -> "Checking available formats…"
    WorkStage.Downloading -> action.progress?.let { "Downloading… ${(it * 100).toInt().coerceIn(0, 100)}%" } ?: "Downloading…"
    WorkStage.Converting -> "Converting for your Kindle…"
}

internal fun optionLabel(option: DownloadOption): String {
    val size = option.sizeBytes?.takeIf { it > 0 }?.let { " · ${DefaultBookRepository.formatFileSize(it)}" }.orEmpty()
    return option.format.name + size
}

/** Big cover, details, format picker, and the two ways to take the book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ResultSheet(
    result: BookResult,
    action: ActionState,
    chosen: DownloadOption?,
    libraryBook: Book?,
    onChoose: (DownloadOption) -> Unit,
    onSend: () -> Unit,
    onAdd: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val working = action as? ActionState.Working
    val busy = working != null
    val added = action is ActionState.Finished
    var expanded by remember(result.id) { mutableStateOf(false) }
    val inLibrary = libraryBook != null || added
    val canTake = chosen != null || inLibrary

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                RemoteCover(result.coverUrl, result.title, Modifier.width(112.dp).height(168.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(result.title, style = MaterialTheme.typography.titleLarge, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (result.author.isNotBlank()) {
                        Text(
                            result.author,
                            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val meta = listOfNotNull(result.year?.toString(), result.language).joinToString(" · ")
                    if (meta.isNotEmpty()) {
                        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ChipRow {
                        TagChip(result.sourceName)
                        if (inLibrary) TagChip("In your library", emphasized = true)
                    }
                }
            }

            result.description?.takeIf { it.isNotBlank() }?.let { text ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Show less" else "Show more") }
                }
            }

            if (libraryBook == null && !added && result.options.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("FORMAT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ChipRow {
                        result.options.forEach { option ->
                            FilterChip(
                                selected = option == chosen,
                                onClick = { onChoose(option) },
                                enabled = !busy,
                                label = { Text(optionLabel(option)) },
                                shape = MaterialTheme.shapes.extraSmall,
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                                    selectedLabelColor = MaterialTheme.colorScheme.surface,
                                ),
                            )
                        }
                    }
                    formatChipLabel(chosen?.format)?.let {
                        Text(
                            when (chosen?.format) {
                                app.pagedrop.discover.BookFormat.EPUB -> "Stock Kindles can’t open EPUB, so PageDrop converts it before sending."
                                else -> it
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (libraryBook == null && !added && working == null && action !is ActionState.Failed) {
                Text(
                    "No downloadable file for this book.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (action) {
                is ActionState.Working -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progressLabel(action), style = MaterialTheme.typography.bodyMedium)
                    val p = action.progress
                    if (action.stage == WorkStage.Downloading && p != null) {
                        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(2.dp))
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                    }
                }
                is ActionState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(action.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onRetry, shape = MaterialTheme.shapes.small) { Text("Retry") }
                }
                is ActionState.Finished -> Text("Added to your library.", style = MaterialTheme.typography.bodyMedium)
                ActionState.Idle -> {}
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSend,
                    enabled = !busy && canTake && action !is ActionState.Failed,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (inLibrary) "Send again" else "Send to Kindle") }
                if (!inLibrary) {
                    OutlinedButton(
                        onClick = onAdd,
                        enabled = !busy && canTake && action !is ActionState.Failed,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Add to library") }
                } else if (added) {
                    TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
            }
        }
    }
}
