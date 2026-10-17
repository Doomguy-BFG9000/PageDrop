package app.pagedrop.ui.kindle

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private fun KindleBookGroup.copiesLabel(): String =
    listOfNotNull(
        "Home screen".takeIf { homeFiles.isNotEmpty() },
        "KOReader".takeIf { koreaderFiles.isNotEmpty() }
    ).joinToString(" · ")

/** One book in the "On your Kindle" list. Tap opens the sheet; long-press starts multi-select. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun KindleBookRow(
    group: KindleBookGroup,
    inLibrary: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .heightIn(min = 56.dp)
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectionMode) Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(group.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    group.copiesLabel() + if (inLibrary) " · In PageDrop" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                formatSize(group.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Bottom bar while books are selected: "Delete N from Kindle". */
@Composable
internal fun KindleSelectionBar(
    count: Int,
    deleteEnabled: Boolean,
    busyHint: String?,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onDelete,
                    enabled = count > 0 && deleteEnabled,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                ) { Text("Delete $count from Kindle") }
                TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            if (busyHint != null) {
                Text(busyHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Bottom sheet for one Kindle book, styled like the Library's book sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KindleBookSheet(
    group: KindleBookGroup,
    inLibrary: Boolean,
    saving: Boolean,
    busyHint: String?,
    onSave: () -> Unit,
    onOpenInLibrary: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleLarge)
                Text(
                    listOf(group.copiesLabel(), formatSize(group.sizeBytes)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                group.files.map { it.folder }.distinct().forEach { folder ->
                    Text(
                        folder,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    if (inLibrary) "In your PageDrop library" else "Not in your PageDrop library",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column {
                if (inLibrary) {
                    SheetAction("Open in library", Icons.Outlined.LibraryBooks, onOpenInLibrary)
                } else {
                    SheetAction(if (saving) "Saving…" else "Save to library", Icons.Outlined.SaveAlt, onSave, enabled = !saving && busyHint == null)
                }
                SheetAction("Delete from Kindle…", Icons.Outlined.Delete, onDelete, destructive = true, enabled = busyHint == null)
                if (busyHint != null) {
                    Text(
                        busyHint,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true
) {
    val color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = color)
            Text(label, color = color, modifier = Modifier.padding(start = 16.dp))
        }
    }
}

/** "Delete “X” from your Kindle?" with which copies to remove and whether to wipe reading progress. */
@Composable
internal fun KindleDeleteDialog(
    groups: List<KindleBookGroup>,
    onConfirm: (home: Boolean, koreader: Boolean, sidecars: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val hasHome = groups.any { it.homeFiles.isNotEmpty() }
    val hasKo = groups.any { it.koreaderFiles.isNotEmpty() }
    var home by remember { mutableStateOf(hasHome) }
    var ko by remember { mutableStateOf(hasKo) }
    var sidecars by remember { mutableStateOf(false) }
    val title = if (groups.size == 1) "Delete “${groups.first().title}” from your Kindle?"
    else "Delete ${groups.size} books from your Kindle?"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        groups.all { it.libraryBookId != null } ->
                            "This can't be undone on the Kindle. Your PageDrop library keeps its own copy."
                        groups.none { it.libraryBookId != null } ->
                            "This can't be undone. PageDrop doesn't have a copy of " +
                                (if (groups.size == 1) "this book." else "these books.")
                        else -> "This can't be undone. Some of these books aren't in your PageDrop library."
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
                if (hasHome) CheckRow("Home screen copy", home) { home = it }
                if (hasKo) CheckRow("KOReader copy", ko) { ko = it }
                CheckRow("Also delete highlights and reading progress", sidecars) { sidecars = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(home, ko, sidecars) },
                enabled = (home && hasHome) || (ko && hasKo)
            ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
    }
}
