package app.pagedrop.ui.book

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pagedrop.metadata.BookMetadataCandidate
import app.pagedrop.metadata.Suggestion
import app.pagedrop.ui.components.HairlineCard
import coil.compose.AsyncImage

/**
 * Editable title/author/cover choice shared by the Add book and Edit details sheets, plus the
 * rules for applying an automatic match: a confident match is pre-filled until the user types.
 * Nothing is saved from here; the sheet's Add/Save button does that.
 */
@Stable
internal class MetadataFormState(initialTitle: String, initialAuthor: String) {
    var title by mutableStateOf(initialTitle)
    var author by mutableStateOf(initialAuthor)
    var coverUrl by mutableStateOf<String?>(null)
    var userEdited by mutableStateOf(false)

    /** The confident match that pre-filled the fields and is waiting for "Use" / "Other matches". */
    var suggested by mutableStateOf<BookMetadataCandidate?>(null)
    var showOthers by mutableStateOf(false)

    /** Where the pre-filled title/author came from; hidden once the user edits. */
    var provenance by mutableStateOf<Suggestion.Source?>(null)

    fun editTitle(value: String) { title = value; userEdited = true; provenance = null }
    fun editAuthor(value: String) { author = value; userEdited = true; provenance = null }

    fun apply(candidate: BookMetadataCandidate) {
        title = candidate.title
        author = candidate.author
        coverUrl = candidate.coverUrl
        suggested = null
        showOthers = false
        provenance = Suggestion.Source.ONLINE
    }

    /** Pre-fills from an automatic [suggestion]; never touches fields the user has typed in. */
    fun applySuggestion(suggestion: Suggestion, hasEmbeddedCover: Boolean) {
        if (userEdited) return
        suggestion.title?.takeIf { it.isNotBlank() }?.let { title = it }
        suggestion.author?.takeIf { it.isNotBlank() }?.let { author = it }
        provenance = suggestion.source.takeIf { suggestion.title != null }
        if (suggestion.source == Suggestion.Source.ONLINE && !hasEmbeddedCover) {
            coverUrl = suggestion.coverUrl ?: suggestion.best?.coverUrl
        }
        suggested = suggestion.best
    }

    fun confirmSuggestion() { suggested?.let(::apply) }
    fun openOthers() { suggested = null; showOthers = true }
}

@Composable
internal fun rememberMetadataFormState(
    key: Any,
    initialTitle: String,
    initialAuthor: String,
    lookupState: BookViewModel.MetadataLookupState,
): MetadataFormState {
    val form = remember(key) { MetadataFormState(initialTitle, initialAuthor) }
    // A new automatic result pre-fills (once), unless the user has already started typing.
    LaunchedEffect(lookupState) {
        form.showOthers = false
        form.suggested = null
        val suggestion = lookupState.suggestion
        if (lookupState.auto && suggestion != null) {
            form.applySuggestion(suggestion, hasEmbeddedCover = lookupState.embeddedCoverPath != null)
        }
    }
    return form
}

@Composable
internal fun MetadataFields(
    form: MetadataFormState,
    lookupState: BookViewModel.MetadataLookupState,
    onFindDetails: () -> Unit,
    coverLabel: String,
    onPickCover: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (lookupState.isSearching && lookupState.auto) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "Looking up details…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        form.suggested?.let { match ->
            MatchedRow(match, onUse = form::confirmSuggestion, onOthers = form::openOthers)
        }

        OutlinedTextField(
            value = form.title,
            onValueChange = form::editTitle,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = form.author,
            onValueChange = form::editAuthor,
            label = { Text("Author") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        form.provenance?.let { source ->
            Text(
                provenanceLabel(source),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = onFindDetails,
                enabled = !lookupState.isSearching && form.title.isNotBlank(),
                modifier = Modifier.weight(1f),
            ) {
                if (lookupState.isSearching && !lookupState.auto) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Searching…")
                } else {
                    Text("Find details")
                }
            }
            OutlinedButton(onClick = onPickCover, modifier = Modifier.weight(1f)) {
                Text(coverLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        lookupState.error?.let { error ->
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (lookupState.offlineNote) {
            Text(
                MSG_OFFLINE,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Manual searches always list matches; automatic ones only list weak matches
        // (no confident best) or when the user asked for "Other matches".
        val showList = lookupState.candidates.isNotEmpty() &&
            (!lookupState.auto || lookupState.best == null || form.showOthers)
        if (showList) {
            Text(
                if (lookupState.auto && lookupState.best == null) "Possible matches — tap one to use it"
                else "Tap a match to use its title, author and cover",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            lookupState.candidates.forEach { candidate ->
                CandidateRow(candidate, onClick = { form.apply(candidate) })
            }
        }
    }
}

internal fun provenanceLabel(source: Suggestion.Source): String = when (source) {
    Suggestion.Source.EMBEDDED -> "From the file"
    Suggestion.Source.FIRST_PAGE -> "From the first page — check it"
    Suggestion.Source.FILENAME -> "From the file name — check it"
    Suggestion.Source.ONLINE -> "Matched online"
}

@Composable
private fun MatchedRow(match: BookMetadataCandidate, onUse: () -> Unit, onOthers: () -> Unit) {
    HairlineCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 4.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CoverThumb(match.coverUrl)
                Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                    Text(
                        "Matched",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(match.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "by ${match.author}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onOthers) { Text("Other matches") }
                TextButton(onClick = onUse) { Text("Use") }
            }
        }
    }
}

@Composable
private fun CandidateRow(candidate: BookMetadataCandidate, onClick: () -> Unit) {
    HairlineCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverThumb(candidate.coverUrl)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(candidate.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(candidate.author.takeIf { it.isNotBlank() }, candidate.year?.toString()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CoverThumb(url: String?) {
    val frame = Modifier.width(36.dp).height(54.dp)
    if (url.isNullOrBlank()) {
        Box(frame.then(Modifier))
    } else {
        AsyncImage(
            model = url.replace("http://", "https://"),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = frame,
        )
    }
}
