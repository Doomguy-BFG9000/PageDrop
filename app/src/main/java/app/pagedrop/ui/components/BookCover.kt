package app.pagedrop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import java.io.File

/**
 * Plain cloth-bound cover: grey block, thin inner border, title in serif (or initials for
 * thumbnails too small to read a title).
 */
@Composable
fun CoverPlaceholder(
    title: String,
    modifier: Modifier = Modifier,
    showTitle: Boolean = true,
) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (showTitle) title else initialsOf(title),
            style = if (showTitle) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = if (showTitle) 5 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Cover image from [coverPath] when present and readable, otherwise a [CoverPlaceholder]. */
@Composable
fun BookCover(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier,
    showTitleOnPlaceholder: Boolean = true,
) {
    val frame = modifier
        .clip(MaterialTheme.shapes.extraSmall)
        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
    if (coverPath.isNullOrBlank()) {
        CoverPlaceholder(title, frame, showTitleOnPlaceholder)
    } else {
        SubcomposeAsyncImage(
            model = File(coverPath),
            contentDescription = null,
            modifier = frame,
            contentScale = ContentScale.Crop,
            loading = { Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) },
            error = { CoverPlaceholder(title, Modifier.fillMaxSize(), showTitleOnPlaceholder) },
        )
    }
}

internal fun initialsOf(title: String): String {
    val letters = title.split(Regex("\\s+"))
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .take(2)
        .joinToString("")
        .uppercase()
    return letters.ifEmpty { "?" }
}
