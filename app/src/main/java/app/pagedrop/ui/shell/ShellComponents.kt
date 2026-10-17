package app.pagedrop.ui.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import app.pagedrop.ui.theme.ButtonShape

/** Flat paper card with a 1dp grey hairline border and no elevation. */
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit
) {
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    val colors = CardDefaults.cardColors(containerColor = containerColor)
    val elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, colors = colors, elevation = elevation, border = border) {
            content()
        }
    } else {
        Card(modifier = modifier, colors = colors, elevation = elevation, border = border) { content() }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 8.dp)
    )
}

/** Two-line navigation row inside a hairline card (title, one-line description, chevron). */
@Composable
fun NavRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    HairlineCard(modifier = modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = ButtonShape) { Text(text) }
}

@Composable
fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ButtonShape,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) { Text(text) }
}

/** Success / failure message with a plain-English fix hint. */
@Composable
fun ResultBanner(success: Boolean, title: String, detail: String?, modifier: Modifier = Modifier) {
    HairlineCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = if (success) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                null,
                tint = if (success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Very low-contrast halftone dot field, echoing the library background. Use sparingly. */
fun Modifier.halftone(color: Color, spacingDp: Float = 10f): Modifier = drawBehind {
    val step = spacingDp.dp.toPx()
    var y = step / 2
    var row = 0
    while (y < size.height) {
        var x = if (row % 2 == 0) step / 2 else step
        while (x < size.width) {
            val fade = 1f - (y / size.height)
            drawCircle(color.copy(alpha = color.alpha * fade), radius = 1.2.dp.toPx() * (0.4f + fade), center = Offset(x, y))
            x += step
        }
        y += step / 1.2f
        row++
    }
}
