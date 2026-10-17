package app.pagedrop.ui.book

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.pagedrop.data.local.database.Book

/** Edit title, author and cover of a library book (existing metadata lookup flow). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditBookSheet(
    book: Book,
    lookupState: BookViewModel.MetadataLookupState,
    selectedCoverUri: Uri?,
    onSearch: (String, String) -> Unit,
    onSuggest: () -> Unit,
    onPickCover: () -> Unit,
    onSave: (String, String, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val form = rememberMetadataFormState(book.uid, book.title, book.author, lookupState)
    LaunchedEffect(book.uid) {
        if (shouldAutoLookup(book.title, book.author)) onSuggest()
    }

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
            Text("Edit details & cover", style = MaterialTheme.typography.titleLarge)
            MetadataFields(
                form = form,
                lookupState = lookupState,
                onFindDetails = { onSearch(form.title, form.author) },
                coverLabel = when {
                    selectedCoverUri != null -> "Custom cover set"
                    form.coverUrl != null -> "Online cover selected"
                    lookupState.embeddedCoverPath != null && book.coverPath == null -> "Cover from file"
                    else -> "Choose cover"
                },
                onPickCover = onPickCover,
            )
            Button(
                onClick = { onSave(form.title, form.author, form.coverUrl) },
                enabled = form.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text("Save details")
            }
        }
    }
}
