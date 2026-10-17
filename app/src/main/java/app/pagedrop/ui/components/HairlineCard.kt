package app.pagedrop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Alpha of the surface drawn over the full-bleed background image. */
const val OverBackgroundSurfaceAlpha = 0.82f

/**
 * Flat, shadowless card separated from its surroundings by a 1dp grey rule, like an e-ink
 * list entry. The surface is slightly translucent so the background image stays visible.
 * Pass [onClick] and/or [onLongClick] to make it interactive.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.small,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
    val clickable = if (onClick != null || onLongClick != null) {
        modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else {
        modifier
    }
    Surface(
        modifier = clickable,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = OverBackgroundSurfaceAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(content = content)
    }
}
