package app.pagedrop.ui.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.pagedrop.ui.components.HairlineCard
import app.pagedrop.ui.components.OverBackgroundSurfaceAlpha
import app.pagedrop.ui.theme.BookSerif

/** Hairline along the bottom edge, shown only while content is scrolled under the bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Modifier.hairlineWhenScrolled(scrollBehavior: TopAppBarScrollBehavior): Modifier {
    val line = MaterialTheme.colorScheme.outlineVariant
    return drawWithContent {
        drawContent()
        if (scrollBehavior.state.overlappedFraction > 0f) {
            val h = 1.dp.toPx()
            drawRect(line, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width, h))
        }
    }
}

/**
 * "PageDrop" with a gear. Transparent at rest so the background image shows through; turns paper-coloured
 * with a hairline once the list scrolls under it, so cards never collide with the title.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryTopBar(onOpenSettings: () -> Unit, scrollBehavior: TopAppBarScrollBehavior) {
    TopAppBar(
        modifier = Modifier.hairlineWhenScrolled(scrollBehavior),
        scrollBehavior = scrollBehavior,
        title = { Text("PageDrop", style = MaterialTheme.typography.titleLarge) },
        actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Outlined.Settings, contentDescription = "Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

/** Contextual bar shown while books are selected. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
    onSelectAll: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
) {
    TopAppBar(
        modifier = Modifier.hairlineWhenScrolled(scrollBehavior),
        scrollBehavior = scrollBehavior,
        title = { Text("$selectedCount selected", style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onClear) {
                Icon(Icons.Outlined.Close, contentDescription = "Cancel selection")
            }
        },
        actions = {
            if (selectedCount < totalCount) {
                TextButton(onClick = onSelectAll) { Text("Select all") }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

internal fun KindleStatus.chipLabel(): String = when (this) {
    KindleStatus.NotConfigured -> "Not set up"
    KindleStatus.Checking -> "Checking Kindle…"
    KindleStatus.Online -> "Kindle connected"
    KindleStatus.Offline -> "Kindle offline"
}

/** Connection chip under the title. A filled dot means reachable, a hollow one means not. */
@Composable
internal fun KindleStatusChip(status: KindleStatus, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurface
    AssistChip(
        onClick = onClick,
        modifier = modifier,
        label = { Text(status.chipLabel(), style = MaterialTheme.typography.labelMedium) },
        leadingIcon = {
            Spacer(
                Modifier
                    .size(10.dp)
                    .then(
                        if (status == KindleStatus.Online) Modifier.background(ink, CircleShape)
                        else Modifier.border(1.5.dp, ink, CircleShape)
                    )
            )
        },
        shape = MaterialTheme.shapes.extraSmall,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = OverBackgroundSurfaceAlpha),
            labelColor = ink,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    )
}

/** Banner shown while no Kindle address is saved. */
@Composable
internal fun NotConnectedBanner(onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    HairlineCard(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Kindle not connected",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onSetUp) { Text("Set up") }
        }
    }
}

/** Primary entry point: looks like a search field, opens the Find screen when tapped. */
@Composable
internal fun SearchEntryField(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface.copy(alpha = OverBackgroundSurfaceAlpha),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Search for a book…",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = BookSerif),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Status chip row, filter chips, List/Grid toggle and the "converting" strip. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryHeader(
    kindleStatus: KindleStatus,
    filter: LibraryFilter,
    viewMode: LibraryViewMode,
    isConverting: Boolean,
    transferProgress: BookViewModel.TransferState.Progress?,
    onStatusClick: () -> Unit,
    onSetUp: () -> Unit,
    onFindBook: () -> Unit,
    onFilterChange: (LibraryFilter) -> Unit,
    onViewModeChange: (LibraryViewMode) -> Unit,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    missingCovers: Int = 0,
    findingCovers: Boolean = false,
    onFindCovers: () -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchEntryField(onClick = onFindBook)
        KindleStatusChip(
            status = kindleStatus,
            onClick = if (kindleStatus == KindleStatus.NotConfigured) onSetUp else onStatusClick,
        )
        if (kindleStatus == KindleStatus.NotConfigured) NotConnectedBanner(onSetUp = onSetUp)

        if (showControls) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibraryFilter.entries.forEach { option ->
                        FilterChip(
                            selected = filter == option,
                            onClick = { onFilterChange(option) },
                            label = { Text(option.label) },
                            shape = MaterialTheme.shapes.extraSmall,
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = OverBackgroundSurfaceAlpha),
                                selectedContainerColor = MaterialTheme.colorScheme.onSurface,
                                selectedLabelColor = MaterialTheme.colorScheme.surface,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = filter == option,
                                borderColor = MaterialTheme.colorScheme.outline,
                                selectedBorderColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
                val toGrid = viewMode == LibraryViewMode.List
                TextButton(
                    onClick = { onViewModeChange(if (toGrid) LibraryViewMode.Grid else LibraryViewMode.List) },
                ) {
                    Icon(
                        imageVector = if (toGrid) Icons.Outlined.GridView else Icons.AutoMirrored.Outlined.ViewList,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (toGrid) "Grid" else "List")
                }
            }
        }

        if (transferProgress != null) {
            HairlineCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val noun = if (transferProgress.total == 1) "book" else "books"
                    Text("Sending ${transferProgress.total} $noun…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }

        if (showControls && (missingCovers > 0 || findingCovers)) {
            HairlineCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = when {
                            findingCovers -> "Looking for covers…"
                            missingCovers == 1 -> "1 book has no cover"
                            else -> "$missingCovers books have no cover"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (findingCovers) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.padding(12.dp).size(18.dp),
                        )
                    } else {
                        TextButton(onClick = onFindCovers) { Text("Find covers") }
                    }
                }
            }
        }

        if (isConverting) {
            HairlineCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Converting…", style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp))
                }
            }
        }
    }
}
