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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import app.pagedrop.data.BookRepository
import app.pagedrop.data.KindleSettings
import app.pagedrop.data.local.database.Book
import app.pagedrop.metadata.BookMetadataCandidate
import app.pagedrop.metadata.BookMetadataLookup
import app.pagedrop.server.PageDropApiClient
import app.pagedrop.transfer.sftp.KindleSftpClient
import app.pagedrop.ui.book.LibraryUiState.Error
import app.pagedrop.ui.book.LibraryUiState.Loading
import app.pagedrop.ui.book.LibraryUiState.Success
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class BookViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bookRepository: BookRepository,
    private val kindleSettings: KindleSettings,
    private val pageDropApiClient: PageDropApiClient? = null,
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = bookRepository
        .getBooks()
        .map<List<Book>, LibraryUiState>(::Success)
        .catch { emit(Error(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Loading)

    private val _transferQueue = MutableStateFlow<List<Book>>(emptyList())
    val transferQueue: StateFlow<List<Book>> = _transferQueue.asStateFlow()

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private val _conversionError = MutableStateFlow<String?>(null)
    val conversionError: StateFlow<String?> = _conversionError.asStateFlow()

    // ── SFTP Transfer State ──

    sealed interface TransferState {
        object Idle : TransferState
        data class Progress(val current: Int, val total: Int, val message: String) : TransferState
        object Success : TransferState
        data class Error(val message: String) : TransferState
    }

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    // ── Connection Test State ──

    private val _isTestingConnection = MutableStateFlow(false)
    val isTestingConnection: StateFlow<Boolean> = _isTestingConnection.asStateFlow()

    private val _connectionTestResult = MutableStateFlow<Result<Unit>?>(null)
    val connectionTestResult: StateFlow<Result<Unit>?> = _connectionTestResult.asStateFlow()

    // ── Settings States (backed by KindleSettings SharedPreferences) ──

    private val _kindleIp = MutableStateFlow(kindleSettings.host)
    val kindleIp: StateFlow<String> = _kindleIp.asStateFlow()

    private val _kindlePort = MutableStateFlow(kindleSettings.port.toString())
    val kindlePort: StateFlow<String> = _kindlePort.asStateFlow()

    private val _kindleUsername = MutableStateFlow(kindleSettings.username)
    val kindleUsername: StateFlow<String> = _kindleUsername.asStateFlow()

    private val _kindlePassword = MutableStateFlow(kindleSettings.password)
    val kindlePassword: StateFlow<String> = _kindlePassword.asStateFlow()

    private val _kindleDirectory = MutableStateFlow(kindleSettings.targetDirectory)
    val kindleDirectory: StateFlow<String> = _kindleDirectory.asStateFlow()

    private val _kindleDirectories = MutableStateFlow(kindleSettings.targetDirectories)
    val kindleDirectories: StateFlow<List<String>> = _kindleDirectories.asStateFlow()

    private val _triggerRescan = MutableStateFlow(kindleSettings.triggerRescan)
    val triggerRescan: StateFlow<Boolean> = _triggerRescan.asStateFlow()

    val isServerConversionAvailable: Boolean
        get() = pageDropApiClient?.isAvailable == true

    data class MetadataLookupState(
        val isSearching: Boolean = false,
        val candidates: List<BookMetadataCandidate> = emptyList(),
        val error: String? = null,
    )

    private val _metadataLookupState = MutableStateFlow(MetadataLookupState())
    val metadataLookupState: StateFlow<MetadataLookupState> = _metadataLookupState.asStateFlow()

    fun addBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
                bookRepository.addBook(context, uri)
            } catch (e: Exception) {
                // Error will surface through uiState flow
            }
        }
    }

    fun addBookWithConversion(context: Context, uri: Uri, outputTitle: String? = null) {
        viewModelScope.launch {
            _isConverting.value = true
            _conversionError.value = null
            try {
                bookRepository.convertAndAddBook(context, uri, outputTitle)
            } catch (e: Exception) {
                _conversionError.value = e.localizedMessage ?: "Conversion failed"
            } finally {
                _isConverting.value = false
            }
        }
    }

    fun addBookWithServerConversion(
        context: Context,
        uri: Uri,
        outputTitle: String? = null,
        outputAuthor: String? = null,
        targetFormat: String = "mobi",
        coverUri: Uri? = null,
        coverUrl: String? = null,
    ) {
        viewModelScope.launch {
            _isConverting.value = true
            _conversionError.value = null
            try {
                val server = pageDropApiClient
                    ?: throw IllegalStateException("PageDrop conversion service is unavailable")
                val connection = server.testConnection()
                if (connection.isFailure) throw connection.exceptionOrNull()!!

                val prepared = server.convertUriToLocalArtifact(
                    uri = uri,
                    outputDir = File(context.filesDir, "server-conversions"),
                    title = outputTitle,
                    author = outputAuthor,
                    targetFormat = targetFormat,
                    coverUri = coverUri,
                    coverUrl = coverUrl,
                ) ?: throw IllegalStateException("The PageDrop conversion service could not convert this file")

                bookRepository.addBookFromFile(
                    Book(
                        title = prepared.title,
                        author = prepared.author,
                        fileName = prepared.file.name,
                        filePath = prepared.file.absolutePath,
                        format = prepared.format,
                        fileSize = prepared.file.length(),
                        coverPath = prepared.coverFile?.absolutePath
                    )
                )
            } catch (e: Exception) {
                _conversionError.value = e.localizedMessage ?: "Server conversion failed"
            } finally {
                _isConverting.value = false
            }
        }
    }

    fun clearConversionError() {
        _conversionError.value = null
    }

    fun searchBookMetadata(title: String, author: String) {
        viewModelScope.launch {
            _metadataLookupState.value = MetadataLookupState(isSearching = true)
            val result = BookMetadataLookup.search(title, author)
            _metadataLookupState.value = result.fold(
                onSuccess = { candidates ->
                    MetadataLookupState(
                        candidates = candidates,
                        error = if (candidates.isEmpty()) "No Google Books matches found" else null,
                    )
                },
                onFailure = { error -> MetadataLookupState(error = error.localizedMessage ?: "Metadata search failed") },
            )
        }
    }

    fun clearMetadataLookup() {
        _metadataLookupState.value = MetadataLookupState()
    }

    fun updateBookMetadata(
        book: Book,
        title: String,
        author: String,
        coverUri: Uri? = null,
        coverUrl: String? = null,
    ) {
        viewModelScope.launch {
            try {
                val coverPath = withContext(Dispatchers.IO) {
                    saveSelectedCover(book, coverUri, coverUrl) ?: book.coverPath
                }
                bookRepository.updateBookMetadata(book, title, author, coverPath)
                _conversionError.value = null
            } catch (error: Exception) {
                _conversionError.value = error.localizedMessage ?: "Could not update book details"
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch {
            // Also remove from transfer queue if queued
            _transferQueue.update { queue -> queue.filter { it.uid != book.uid } }
            bookRepository.deleteBook(book)
        }
    }

    private fun saveSelectedCover(book: Book, coverUri: Uri?, coverUrl: String?): String? {
        if (coverUri == null && coverUrl.isNullOrBlank()) return null
        val coversDir = File(appContext.filesDir, "covers")
        if (!coversDir.exists()) coversDir.mkdirs()
        val coverFile = File(coversDir, "metadata_${book.uid}.jpg")
        val saved = when {
            coverUri != null -> appContext.contentResolver.openInputStream(coverUri)?.use { input ->
                coverFile.outputStream().use(input::copyTo)
                coverFile.length() > 0
            } ?: false
            else -> BookMetadataLookup.downloadCover(coverUrl!!, coverFile)
        }
        return coverFile.absolutePath.takeIf { saved } ?: run {
            coverFile.delete()
            throw IllegalStateException("Could not save the selected cover")
        }
    }

    fun queueForTransfer(book: Book) {
        _transferQueue.update { queue ->
            if (queue.any { it.uid == book.uid }) queue
            else queue + book
        }
    }

    fun removeFromQueue(book: Book) {
        _transferQueue.update { queue -> queue.filter { it.uid != book.uid } }
    }

    fun isQueued(book: Book): Boolean {
        return _transferQueue.value.any { it.uid == book.uid }
    }

    fun toggleQueued(book: Book) {
        if (isQueued(book)) removeFromQueue(book) else queueForTransfer(book)
    }

    fun clearQueue() {
        _transferQueue.update { emptyList() }
    }

    // ── Connection Settings Updates ──

    fun updateKindleIp(value: String) {
        _kindleIp.value = value
        kindleSettings.host = value
    }

    fun updateKindlePort(value: String) {
        _kindlePort.value = value
        val portVal = value.toIntOrNull() ?: 22
        kindleSettings.port = portVal
    }

    fun updateKindleUsername(value: String) {
        _kindleUsername.value = value
        kindleSettings.username = value
    }

    fun updateKindlePassword(value: String) {
        _kindlePassword.value = value
        kindleSettings.password = value
    }

    fun updateKindleDirectory(value: String) {
        _kindleDirectory.value = value
        kindleSettings.targetDirectory = value
        updateKindleDirectories(_kindleDirectories.value.toMutableList().also {
            if (it.isEmpty()) it.add(value) else it[0] = value
        })
    }

    fun updateKindleDirectories(value: List<String>) {
        val normalized = value.map { it.trim() }.filter { it.isNotBlank() }
            .ifEmpty { listOf("/mnt/us/documents") }
        _kindleDirectories.value = normalized
        _kindleDirectory.value = normalized.first()
        kindleSettings.targetDirectories = normalized
    }

    fun updateTriggerRescan(value: Boolean) {
        _triggerRescan.value = value
        kindleSettings.triggerRescan = value
    }

    // ── SFTP Connection Test & Transfer ──

    fun testConnection() {
        viewModelScope.launch {
            _isTestingConnection.value = true
            _connectionTestResult.value = null
            
            val hostVal = _kindleIp.value
            val portVal = _kindlePort.value.toIntOrNull() ?: 22
            val userVal = _kindleUsername.value
            val passVal = _kindlePassword.value

            val result = KindleSftpClient.testConnection(
                host = hostVal,
                port = portVal,
                user = userVal,
                pass = passVal
            )

            _connectionTestResult.value = result
            _isTestingConnection.value = false
        }
    }

    fun clearConnectionTestResult() {
        _connectionTestResult.value = null
    }

    fun resetTransferState() {
        _transferState.value = TransferState.Idle
    }

    fun transferBooksToKindle() {
        val booksToTransfer = _transferQueue.value
        if (booksToTransfer.isEmpty()) return

        viewModelScope.launch {
            _transferState.value = TransferState.Idle

            val hostVal = _kindleIp.value
            val portVal = _kindlePort.value.toIntOrNull() ?: 22
            val userVal = _kindleUsername.value
            val passVal = _kindlePassword.value
            val directoriesVal = _kindleDirectories.value
            val rescanVal = _triggerRescan.value

            _transferState.value = TransferState.Progress(0, booksToTransfer.size, "Checking Kindle connection...")
            val connection = KindleSftpClient.testConnection(hostVal, portVal, userVal, passVal)
            if (connection.isFailure) {
                val detail = connection.exceptionOrNull()?.localizedMessage ?: "unknown error"
                _transferState.value = TransferState.Error("Can't reach your Kindle at $hostVal:$portVal. Check that it is on the same network and SSH is running. $detail")
                return@launch
            }

            // Prepare thumbnail bytes from cover files
            val thumbs = mutableMapOf<Int, ByteArray>()
            for (book in booksToTransfer) {
                book.coverPath?.let { path ->
                    try {
                        val coverFile = File(path)
                        if (coverFile.exists()) {
                            val source = BitmapFactory.decodeFile(path)
                            if (source != null) {
                                val scaled = Bitmap.createScaledBitmap(source, 330, 430, true)
                                if (scaled != source) source.recycle()
                                val out = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                                scaled.recycle()
                                thumbs[book.uid] = out.toByteArray()
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            val result = KindleSftpClient.transferBooks(
                books = booksToTransfer,
                host = hostVal,
                port = portVal,
                user = userVal,
                pass = passVal,
                directories = directoriesVal,
                triggerRescan = rescanVal,
                thumbnailBytes = thumbs
            ) { current, total, message ->
                _transferState.value = TransferState.Progress(current, total, message)
            }

            if (result.isSuccess) {
                _transferState.value = TransferState.Success
                bookRepository.markTransferredBookIds(
                    booksToTransfer.map { it.uid }
                )
                clearQueue()
            } else {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                _transferState.value = TransferState.Error(errorMsg)
            }
        }
    }
}

sealed interface LibraryUiState {
    object Loading : LibraryUiState
    data class Error(val throwable: Throwable) : LibraryUiState
    data class Success(val data: List<Book>) : LibraryUiState
}
