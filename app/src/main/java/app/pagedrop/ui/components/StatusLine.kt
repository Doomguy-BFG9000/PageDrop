package app.pagedrop.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow

enum class StatusTone { Normal, Active, Error }

/** Small grey one-liner such as "On Kindle · 3 Oct". Errors use the theme's error colour. */
@Composable
fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    tone: StatusTone = StatusTone.Normal,
) {
    val color: Color = when (tone) {
        StatusTone.Normal -> MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.Active -> MaterialTheme.colorScheme.onSurface
        StatusTone.Error -> MaterialTheme.colorScheme.error
    }
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
