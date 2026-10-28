/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.pagedrop.ui.book

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.data.local.database.Book
import app.pagedrop.ui.discover.FindScreen
import java.io.File

/**
 * The Library tab. Reads everything from [BookViewModel]; navigation is handed back through
 * the callbacks so the shell decides where the status chip, gear and banner lead.
 */
@Composable
fun BookScreen(
    modifier: Modifier = Modifier,
    onOpenKindle: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnectionSetup: () -> Unit,
    onOpenArticle: () -> Unit,
    onOpenBookSources: () -> Unit = {},
    viewModel: BookViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sendingIds by viewModel.sendingIds.collectAsStateWithLifecycle()
    val failedIds by viewModel.failedIds.collectAsStateWithLifecycle()
    val isConverting by viewModel.isConverting.collectAsStateWithLifecycle()
    val coverSearchTried by viewModel.coverSearchTried.collectAsStateWithLifecycle()
    val findingCovers by viewModel.findingCovers.collectAsStateWithLifecycle()
    val conversionError by viewModel.conversionError.collectAsStateWithLifecycle()
    val transferState by viewModel.transferState.collectAsStateWithLifecycle()
    val metadataLookupState by viewModel.metadataLookupState.collectAsStateWithLifecycle()
    val kindleStatus by viewModel.kindleStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val books = (uiState as? LibraryUiState.Success)?.data.orEmpty()

    var filter by rememberSaveable { mutableStateOf(LibraryFilter.All) }
    var viewMode by rememberSaveable { mutableStateOf(LibraryViewMode.Grid) }
    var selectedIds by remember { mutableStateOf(emptySet<Int>()) }
    var sheetBookId by remember { mutableStateOf<Int?>(null) }
    var editBookId by remember { mutableStateOf<Int?>(null) }
    var pendingAdd by remember { mutableStateOf<PendingAdd?>(null) }
    var deleteTargets by remember { mutableStateOf<List<Book>?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var showFind by rememberSaveable { mutableStateOf(false) }

    val selectionMode = selectedIds.isNotEmpty()
    val transferProgress = transferState as? BookViewModel.TransferState.Progress

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshKindleStatus() }
    BackHandler(enabled = selectionMode && !showFind) { selectedIds = emptySet() }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) coverUri = uri
    }

    /** Opens the Add book sheet for a file the user picked or shared to PageDrop. */
    fun startAdd(uri: Uri) {
        val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        } ?: uri.lastPathSegment ?: "Untitled"
        coverUri = null
        viewModel.clearMetadataLookup()
        pendingAdd = PendingAdd(
            uri = uri,
            displayName = displayName,
            format = DefaultBookRepository.detectFormat(displayName),
            initialTitle = displayName.substringBeforeLast("."),
        )
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) startAdd(uri)
    }

    // A single file shared to PageDrop opens the same Add book sheet as picking one.
    val sharedUri by viewModel.pendingSharedUri.collectAsStateWithLifecycle()
    LaunchedEffect(sharedUri) {
        sharedUri?.let { uri ->
            startAdd(uri)
            viewModel.consumeSharedUri(uri)
        }
    }
    val launchAddBook = {
        filePicker.launch(
            arrayOf(
                "application/pdf",
                "text/plain",
                "application/x-mobipocket-ebook", // .mobi
                "application/epub+zip",            // .epub
                "application/octet-stream",        // .azw3 fallback
                "*/*",                             // fallback for Kindle formats
            )
        )
    }

    // Import / metadata errors
    LaunchedEffect(conversionError) {
        conversionError?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearConversionError()
        }
    }

    // One-shot results from the ViewModel. Snackbars are for results and errors only;
    // sending progress lives in the library header and on the book cards.
    LaunchedEffect(viewModel) {
        coroutineScope {
            viewModel.events.collect { event ->
                launch {
                    when (event) {
                        is BookViewModel.LibraryEvent.Message -> snackbarHostState.showSnackbar(event.text)
                        is BookViewModel.LibraryEvent.TransferSucceeded -> snackbarHostState.showSnackbar("Sent to Kindle")
                        is BookViewModel.LibraryEvent.ConvertedCopyAdded -> {
                            val result = snackbarHostState.showSnackbar(
                                message = "Converted copy added",
                                actionLabel = "Remove original",
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed) viewModel.removeOriginal(event.originalBookId)
                        }
                        is BookViewModel.LibraryEvent.TransferFailed -> {
                            val error = event.error
                            val result = snackbarHostState.showSnackbar(
                                message = transferErrorMessage(error),
                                actionLabel = if (error.isConnectionProblem) "Check connection" else "Retry",
                                withDismissAction = true,
                                duration = SnackbarDuration.Long,
                            )
                            if (result == SnackbarResult.ActionPerformed && !error.isConnectionProblem) {
                                viewModel.sendBooks(event.books)
                            } else {
                                viewModel.dismissTransferResult()
                                if (result == SnackbarResult.ActionPerformed) {
                                    if (viewModel.kindleStatus.value == KindleStatus.NotConfigured) onOpenConnectionSetup() else onOpenKindle()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun closeAddSheets() {
        pendingAdd = null
        editBookId = null
        coverUri = null
        viewModel.clearMetadataLookup()
    }

    // ── Sheets and dialogs ──

    val sheetBook = books.firstOrNull { it.uid == sheetBookId }
    if (sheetBook != null) {
        BookSheet(
            book = sheetBook,
            transfer = when (sheetBook.uid) {
                in sendingIds -> BookTransfer.Sending
                in failedIds -> BookTransfer.Failed
                else -> BookTransfer.Idle
            },
            deleteBlocked = sheetBook.uid in sendingIds,
            onSend = {
                sheetBookId = null
                viewModel.sendBooks(listOf(sheetBook))
            },
            onEdit = {
                sheetBookId = null
                coverUri = null
                viewModel.clearMetadataLookup()
                editBookId = sheetBook.uid
            },
            onConvert = {
                sheetBookId = null
                coverUri = null
                viewModel.clearMetadataLookup()
                pendingAdd = PendingAdd(
                    uri = Uri.fromFile(File(sheetBook.filePath)),
                    displayName = sheetBook.fileName,
                    format = sheetBook.format,
                    initialTitle = sheetBook.title,
                    initialAuthor = sheetBook.author,
                    convertOnly = true,
                    originalBookId = sheetBook.uid,
                )
            },
            onDelete = {
                sheetBookId = null
                deleteTargets = listOf(sheetBook)
            },
            onDismiss = { sheetBookId = null },
        )
    }

    books.firstOrNull { it.uid == editBookId }?.let { book ->
        EditBookSheet(
            book = book,
            lookupState = metadataLookupState,
            selectedCoverUri = coverUri,
            onSearch = viewModel::searchBookMetadata,
            onSuggest = { viewModel.suggestForBook(book) },
            onPickCover = { coverPicker.launch(arrayOf("image/*")) },
            onSave = { title, author, coverUrl ->
                val embedded = metadataLookupState.embeddedCoverPath
                    ?.takeIf { coverUri == null && coverUrl == null && book.coverPath == null }
                    ?.let { Uri.fromFile(File(it)) }
                viewModel.updateBookMetadata(book, title, author, coverUri ?: embedded, coverUrl)
                closeAddSheets()
            },
            onDismiss = ::closeAddSheets,
        )
    }

    pendingAdd?.let { pending ->
        AddBookSheet(
            pending = pending,
            serverConversionAvailable = viewModel.isServerConversionAvailable,
            serverNoticeAnswered = viewModel.isUploadNoticeAnswered,
            lookupState = metadataLookupState,
            selectedCoverUri = coverUri,
            onSearch = viewModel::searchBookMetadata,
            onSuggest = { viewModel.suggestForUri(pending.uri, pending.displayName, pending.format) },
            onPickCover = { coverPicker.launch(arrayOf("image/*")) },
            onConfirm = { decision ->
                val embeddedCover = metadataLookupState.embeddedCoverPath
                    ?.takeIf { coverUri == null && decision.coverUrl == null }
                    ?.let { Uri.fromFile(File(it)) }
                val effectiveCover = coverUri ?: embeddedCover
                val options = BookViewModel.AddOptions(
                    title = decision.title,
                    author = decision.author,
                    coverUri = effectiveCover,
                    coverUrl = decision.coverUrl,
                    sendAfterAdd = decision.sendToKindle,
                    originalBookId = pending.originalBookId,
                )
                when {
                    !decision.convert -> viewModel.addBook(context, pending.uri, options)
                    viewModel.isServerConversionAvailable -> viewModel.addBookWithServerConversion(
                        context = context,
                        uri = pending.uri,
                        outputTitle = decision.title ?: pending.initialTitle,
                        outputAuthor = decision.author ?: pending.initialAuthor.ifBlank { "Unknown Author" },
                        targetFormat = decision.serverFormat.lowercase(),
                        coverUri = effectiveCover,
                        coverUrl = decision.coverUrl,
                        sendAfterAdd = decision.sendToKindle,
                        originalBookId = pending.originalBookId,
                    )
                    else -> viewModel.addBookWithConversion(context, pending.uri, decision.title, options)
                }
                closeAddSheets()
            },
            onDismiss = ::closeAddSheets,
        )
    }

    deleteTargets?.let { targets ->
        AlertDialog(
            onDismissRequest = { deleteTargets = null },
            shape = MaterialTheme.shapes.small,
            title = { Text(if (targets.size == 1) "Delete this book?" else "Delete ${targets.size} books?") },
            text = {
                Text(
                    if (targets.size == 1) "“${targets.first().title}” will be removed from PageDrop. Copies already on your Kindle are not touched."
                    else "They will be removed from PageDrop. Copies already on your Kindle are not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteBooks(targets)
                    selectedIds = selectedIds - targets.map { it.uid }.toSet()
                    deleteTargets = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTargets = null }) { Text("Cancel") } },
        )
    }

    Box(modifier = modifier) {
    LibraryScreenContent(
        uiState = uiState,
        kindleStatus = kindleStatus,
        filter = filter,
        viewMode = viewMode,
        selectedIds = selectedIds,
        sendingIds = sendingIds,
        failedIds = failedIds,
        isConverting = isConverting,
        isSending = transferProgress != null,
        transferProgress = transferProgress,
        snackbarHostState = snackbarHostState,
        modifier = Modifier,
        missingCovers = (uiState as? LibraryUiState.Success)?.data.orEmpty()
            .count { it.coverPath.isNullOrBlank() && it.uid !in coverSearchTried },
        findingCovers = findingCovers,
        actions = LibraryActions(
            onFindCovers = viewModel::findMissingCovers,
            onAddBook = launchAddBook,
            onFindBook = { showFind = true },
            onOpenArticle = onOpenArticle,
            onOpenSettings = onOpenSettings,
            onStatusClick = onOpenKindle,
            onSetUp = onOpenConnectionSetup,
            onFilterChange = {
                if (it != filter) selectedIds = emptySet()
                filter = it
            },
            onViewModeChange = { viewMode = it },
            onBookClick = { book ->
                if (selectionMode) selectedIds = selectedIds.toggle(book.uid) else sheetBookId = book.uid
            },
            onBookLongClick = { book -> selectedIds = selectedIds + book.uid },
            onClearSelection = { selectedIds = emptySet() },
            onSelectAll = { selectedIds = books.filter(filter::matches).map { it.uid }.toSet() },
            onSendSelected = {
                viewModel.sendBooks(books.filter { filter.matches(it) && it.uid in selectedIds })
                selectedIds = emptySet()
            },
            onDeleteSelected = { deleteTargets = books.filter { filter.matches(it) && it.uid in selectedIds } },
        ),
    )
    if (showFind) {
        // Overlay (not a separate destination) so a finished "Send to Kindle" goes straight
        // through this screen's own send flow and progress/snackbars.
        FindScreen(
            onBack = { showFind = false },
            onSendBook = { book ->
                showFind = false
                viewModel.sendBooks(listOf(book))
            },
            onOpenSources = { showFind = false; onOpenBookSources() },
        )
    }
    }
}

private fun Set<Int>.toggle(id: Int): Set<Int> = if (id in this) this - id else this + id

private fun transferErrorMessage(error: BookViewModel.TransferState.Error): String =
    if (error.isConnectionProblem) "Can’t reach your Kindle."
    else "Couldn’t send to Kindle. ${error.message.lineSequence().first().take(80)}"
