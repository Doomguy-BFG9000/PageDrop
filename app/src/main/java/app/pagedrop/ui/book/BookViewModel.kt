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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.util.Log
import app.pagedrop.converter.BookConverter
import app.pagedrop.data.BookRepository
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.data.KindleSettings
import app.pagedrop.data.local.database.Book
import app.pagedrop.metadata.BookMetadataCandidate
import app.pagedrop.metadata.BookMetadataLookup
import app.pagedrop.metadata.BookNameCleaner
import app.pagedrop.metadata.MetadataLookupResult
import app.pagedrop.metadata.MetadataSuggester
import app.pagedrop.metadata.Suggestion
import app.pagedrop.share.PendingSharedFile
import kotlinx.coroutines.Job
import java.io.IOException
import app.pagedrop.server.PageDropApiClient
import app.pagedrop.transfer.sftp.KindleSftpClient
import app.pagedrop.ui.book.LibraryUiState.Error
import app.pagedrop.ui.book.LibraryUiState.Loading
import app.pagedrop.ui.book.LibraryUiState.Success
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@HiltViewModel
class BookViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val bookRepository: BookRepository,
    private val kindleSettings: KindleSettings,
    private val pageDropApiClient: PageDropApiClient? = null,
    private val pendingSharedFile: PendingSharedFile = PendingSharedFile(),
) : ViewModel() {

    /** A single file shared to PageDrop, waiting for the Add book sheet. */
    val pendingSharedUri: StateFlow<Uri?> = pendingSharedFile.uri

    fun consumeSharedUri(uri: Uri) = pendingSharedFile.consume(uri)


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
        data class Error(val message: String, val isConnectionProblem: Boolean = false) : TransferState
    }

    private val _transferState = MutableStateFlow<TransferState>(TransferState.Idle)
    val transferState: StateFlow<TransferState> = _transferState.asStateFlow()

    /** One-shot results for the library UI; buffered so nothing is lost while the tab is away. */
    sealed interface LibraryEvent {
        data class Message(val text: String) : LibraryEvent
        data object TransferSucceeded : LibraryEvent
        data class TransferFailed(val error: TransferState.Error, val books: List<Book>) : LibraryEvent
        data class ConvertedCopyAdded(val originalBookId: Int) : LibraryEvent
    }

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    /** Books of the transfer that is running right now (empty when idle). */
    private val _sendingIds = MutableStateFlow<Set<Int>>(emptySet())
    val sendingIds: StateFlow<Set<Int>> = _sendingIds.asStateFlow()

    /** Books whose last send failed; cleared by the next successful send. */
    private val _failedIds = MutableStateFlow<Set<Int>>(emptySet())
    val failedIds: StateFlow<Set<Int>> = _failedIds.asStateFlow()

    /** Send requests made while another transfer was running; sent when it finishes. */
    private var pendingSend: List<Book> = emptyList()

    // ── Kindle reachability (lightweight TCP check for the library status chip) ──

    private val _kindleStatus = MutableStateFlow(initialKindleStatus())
    val kindleStatus: StateFlow<KindleStatus> = _kindleStatus.asStateFlow()

    /** Replaceable in tests; true when something accepts TCP connections on host:port. */
    internal var reachabilityCheck: suspend (String, Int) -> Boolean = ::canConnectTcp

    /** Dispatchers for blocking and CPU work; tests replace them with their test dispatcher. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    internal var defaultDispatcher: CoroutineDispatcher = Dispatchers.Default

    /** SFTP seams, replaceable in tests so no real sockets are opened. */
    internal var sftpTestConnection: suspend (String, Int, String, String) -> Result<Unit> =
        { host, port, user, pass -> KindleSftpClient.testConnection(host, port, user, pass) }
    internal var sftpTransfer: suspend (
        List<Book>, String, Int, String, String, List<String>, Boolean, Map<Int, ByteArray>, (Int, Int, String) -> Unit,
    ) -> Result<Unit> = { books, host, port, user, pass, dirs, rescan, thumbs, progress ->
        KindleSftpClient.transferBooks(
            books = books, host = host, port = port, user = user, pass = pass,
            directories = dirs, triggerRescan = rescan, thumbnailBytes = thumbs, onProgress = progress,
        )
    }

    private fun initialKindleStatus(): KindleStatus =
        if (kindleSettings.host.isBlank()) KindleStatus.NotConfigured else KindleStatus.Checking

    /** Re-reads the saved connection settings and probes the Kindle. Call on resume. */
    fun refreshKindleStatus() {
        reloadSettings()
        val host = kindleSettings.host.trim()
        if (host.isBlank()) {
            _kindleStatus.value = KindleStatus.NotConfigured
            return
        }
        val port = kindleSettings.port
        _kindleStatus.value = KindleStatus.Checking
        viewModelScope.launch {
            val reachable = try {
                reachabilityCheck(host, port)
            } catch (_: Exception) {
                false
            }
            _kindleStatus.value = if (reachable) KindleStatus.Online else KindleStatus.Offline
        }
    }

    /** Pulls host/port/credentials back from [KindleSettings], which Settings may have changed. */
    private fun reloadSettings() {
        _kindleIp.value = kindleSettings.host
        _kindlePort.value = kindleSettings.port.toString()
        _kindleUsername.value = kindleSettings.username
        loadPassword()
        _kindleDirectories.value = kindleSettings.targetDirectories
        _kindleDirectory.value = kindleSettings.targetDirectory
        _triggerRescan.value = kindleSettings.triggerRescan
    }

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

    // Starts empty: decrypting the password needs the Keystore, which must not run on the main thread.
    private val _kindlePassword = MutableStateFlow("")
    val kindlePassword: StateFlow<String> = _kindlePassword.asStateFlow()

    private var passwordLoad: kotlinx.coroutines.Job? = null
    @Volatile private var passwordEdited = false

    private fun loadPassword() {
        passwordLoad = viewModelScope.launch {
            val saved = withContext(ioDispatcher) { kindleSettings.password }
            if (!passwordEdited) _kindlePassword.value = saved
        }
    }

    init { loadPassword() }

    private val _kindleDirectory = MutableStateFlow(kindleSettings.targetDirectory)
    val kindleDirectory: StateFlow<String> = _kindleDirectory.asStateFlow()

    private val _kindleDirectories = MutableStateFlow(kindleSettings.targetDirectories)
    val kindleDirectories: StateFlow<List<String>> = _kindleDirectories.asStateFlow()

    private val _triggerRescan = MutableStateFlow(kindleSettings.triggerRescan)
    val triggerRescan: StateFlow<Boolean> = _triggerRescan.asStateFlow()

    val isServerConversionAvailable: Boolean
        get() = pageDropApiClient?.isAvailable == true

    /** Whether the user has already answered the first-upload notice (so the sheet may say "by the server"). */
    val isUploadNoticeAnswered: Boolean
        get() = pageDropApiClient?.uploadNoticeAnswered == true

    /** Asked before any request to the server (even sign-in); false means nothing may leave the phone. Replaceable in tests. */
    internal var confirmUploadFn: suspend () -> Boolean = { pageDropApiClient?.confirmUpload() == true }

    /** The only place a book/cover is sent to the server from this screen; reached only after [confirmUploadFn]. Replaceable in tests. */
    internal var serverConvertFn: suspend (Context, Uri, String?, String?, String, String?) -> PageDropApiClient.PreparedArtifact =
        { context, uri, title, author, format, coverPath ->
            val server = pageDropApiClient
                ?: throw IllegalStateException("PageDrop conversion service is unavailable")
            val connection = server.testConnection()
            if (connection.isFailure) throw connection.exceptionOrNull()!!
            server.convertUriToLocalArtifact(
                uri = uri,
                outputDir = File(context.filesDir, "server-conversions"),
                title = title,
                author = author,
                targetFormat = format,
                coverUri = coverPath?.let { Uri.fromFile(File(it)) },
                coverUrl = null,
            ) ?: throw IllegalStateException("The PageDrop conversion service could not convert this file")
        }

    internal var sourceFormatFn: (Context, Uri) -> String = { context, uri ->
        DefaultBookRepository.detectFormat(
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val col = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && c.moveToFirst()) c.getString(col) else null
            } ?: uri.lastPathSegment.orEmpty()
        )
    }

    /**
     * @property best confident top match (safe to pre-fill, the user still confirms); null for weak matches.
     * @property auto true when the lookup started by itself (sheet opened), false for "Find details".
     * @property offlineNote the automatic lookup could not run because the phone is offline.
     * @property searched a lookup has finished (with or without results).
     */
    data class MetadataLookupState(
        val isSearching: Boolean = false,
        val candidates: List<BookMetadataCandidate> = emptyList(),
        val best: BookMetadataCandidate? = null,
        val error: String? = null,
        val auto: Boolean = false,
        val offlineNote: Boolean = false,
        val searched: Boolean = false,
        /** What the file itself (or the catalogs) suggested for the open sheet; null for manual searches. */
        val suggestion: Suggestion? = null,
        /** Cover read from the file, saved as a pending cover; the default when no other cover is chosen. */
        val embeddedCoverPath: String? = null,
    )

    private val _metadataLookupState = MutableStateFlow(MetadataLookupState())
    val metadataLookupState: StateFlow<MetadataLookupState> = _metadataLookupState.asStateFlow()

    private var lookupJob: Job? = null

    /** Replaceable in tests. */
    internal var lookupFn: suspend (String, String) -> MetadataLookupResult = { t, a -> BookMetadataLookup.lookup(t, a) }
    internal var fetchCoverFn: suspend (String) -> ByteArray? = { BookMetadataLookup.fetchCover(it) }
    internal var suggestFn: suspend (File, String, String) -> Suggestion =
        { file, format, name -> MetadataSuggester.suggest(file, format, name) }
    internal var copyToCacheFn: suspend (Context, Uri) -> File? = { c, u -> MetadataSuggester.copyToCache(c, u) }
    internal var coversDir: () -> File = { File(appContext.filesDir, "covers") }

    /**
     * Extra choices made in the Add book sheet. Blank/null values keep whatever the import
     * detected; [sendAfterAdd] queues the new book for the Kindle as soon as it is saved.
     */
    data class AddOptions(
        val title: String? = null,
        val author: String? = null,
        val coverUri: Uri? = null,
        val coverUrl: String? = null,
        val sendAfterAdd: Boolean = false,
        /** Set when this is a converted copy of a library book, so the UI can offer to remove it. */
        val originalBookId: Int? = null,
    )

    fun addBook(context: Context, uri: Uri, options: AddOptions = AddOptions()) {
        viewModelScope.launch {
            try {
                val cover = resolveCover(options.coverUri, options.coverUrl)
                val book = bookRepository.addBookWithMetadata(context, uri, options.title, options.author, cover)
                completeAdd(book, options)
            } catch (e: Exception) {
                _conversionError.value = e.localizedMessage ?: "Could not add this book"
            }
        }
    }

    fun addBookWithConversion(
        context: Context,
        uri: Uri,
        outputTitle: String? = null,
        options: AddOptions = AddOptions(),
    ) {
        viewModelScope.launch {
            _isConverting.value = true
            _conversionError.value = null
            try {
                val cover = resolveCover(options.coverUri, options.coverUrl)
                val title = outputTitle?.takeIf { it.isNotBlank() } ?: options.title
                val book = bookRepository.convertAndAddBookWithMetadata(context, uri, title, options.author, cover)
                completeAdd(book, options)
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
        sendAfterAdd: Boolean = false,
        originalBookId: Int? = null,
    ) {
        viewModelScope.launch {
            _isConverting.value = true
            _conversionError.value = null
            // Consent comes before any network request (sign-in, connection test, book or cover upload).
            val mayUpload = try {
                confirmUploadFn()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!mayUpload) {
                try {
                    convertWithoutServer(context, uri, outputTitle, outputAuthor, targetFormat, coverUri, coverUrl, sendAfterAdd, originalBookId)
                } finally {
                    _isConverting.value = false
                }
                return@launch
            }
            // Fetch the cover once; the server and the final applyMetadata both use this file.
            val cover = resolveCover(coverUri, coverUrl)
            try {
                val prepared = serverConvertFn(context, uri, outputTitle, outputAuthor, targetFormat, cover)

                val book = Book(
                    title = prepared.title,
                    author = prepared.author,
                    fileName = prepared.file.name,
                    filePath = prepared.file.absolutePath,
                    format = prepared.format,
                    fileSize = prepared.file.length(),
                    coverPath = prepared.coverFile?.absolutePath
                )
                val id = bookRepository.addBookFromFile(book)
                var saved = book.copy(uid = id.toInt())
                val chosenAuthor = outputAuthor?.takeIf { it.isNotBlank() && !BookNameCleaner.isPlaceholderAuthor(it) }
                if (!outputTitle.isNullOrBlank() || chosenAuthor != null || cover != null) {
                    try {
                        saved = bookRepository.applyMetadata(
                            saved,
                            outputTitle?.takeIf { it.isNotBlank() } ?: saved.title,
                            chosenAuthor ?: saved.author,
                            cover ?: saved.coverPath,
                        )
                    } catch (e: Exception) {
                        Log.w("BookViewModel", "Could not apply metadata to server result: ${e.message}")
                    }
                }
                originalBookId?.let { _events.trySend(LibraryEvent.ConvertedCopyAdded(it)) }
                if (sendAfterAdd) sendBooks(listOf(saved))
            } catch (e: Exception) {
                Log.w("BookViewModel", "Server conversion failed: ${e.message}")
                val sourceFormat = sourceFormatFn(context, uri)
                if (targetFormat.equals("mobi", ignoreCase = true) && BookConverter.canConvert(sourceFormat)) {
                    // The phone's own converter makes MOBI too; use it rather than losing the book.
                    _events.trySend(LibraryEvent.Message("Conversion server unavailable — converting on this phone instead"))
                    try {
                        val book = bookRepository.convertAndAddBookWithMetadata(context, uri, outputTitle, outputAuthor, cover)
                        completeAdd(
                            book,
                            AddOptions(sendAfterAdd = sendAfterAdd, originalBookId = originalBookId),
                        )
                    } catch (local: Exception) {
                        _conversionError.value = local.localizedMessage ?: "Conversion failed"
                    }
                } else {
                    _conversionError.value = e.localizedMessage ?: "Server conversion failed"
                }
            } finally {
                _isConverting.value = false
            }
        }
    }

    /** The user kept everything on the phone: MOBI is made here; server-only formats are not uploaded. */
    private suspend fun convertWithoutServer(
        context: Context,
        uri: Uri,
        outputTitle: String?,
        outputAuthor: String?,
        targetFormat: String,
        coverUri: Uri?,
        coverUrl: String?,
        sendAfterAdd: Boolean,
        originalBookId: Int?,
    ) {
        if (!targetFormat.equals("mobi", ignoreCase = true) || !BookConverter.canConvert(sourceFormatFn(context, uri))) {
            _events.trySend(LibraryEvent.Message("Server conversion is off — choose MOBI to convert on this phone"))
            return
        }
        try {
            val cover = resolveCover(coverUri, coverUrl)
            val book = bookRepository.convertAndAddBookWithMetadata(context, uri, outputTitle, outputAuthor, cover)
            completeAdd(book, AddOptions(sendAfterAdd = sendAfterAdd, originalBookId = originalBookId))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _conversionError.value = e.localizedMessage ?: "Conversion failed"
        }
    }

    /** Metadata is already in the saved [book]; offer "remove original" and optionally send it. */
    private fun completeAdd(book: Book, options: AddOptions) {
        options.originalBookId?.let { _events.trySend(LibraryEvent.ConvertedCopyAdded(it)) }
        if (options.sendAfterAdd) sendBooks(listOf(book))
    }

    fun clearConversionError() {
        _conversionError.value = null
    }

    /** The user pressed "Find details". */
    fun searchBookMetadata(title: String, author: String) = startLookup(title, author, auto = false)

    /**
     * Sheet opened for a picked/shared file: read what the file itself says (then the catalogs) and
     * offer it. Never applies anything; the form decides what is safe to pre-fill.
     */
    fun suggestForUri(uri: Uri, displayName: String, format: String) =
        suggestForCopy({ copyToCacheFn(appContext, uri) }, displayName, format)

    internal fun suggestForCopy(copy: suspend () -> File?, displayName: String, format: String) = launchLookup(auto = true) {
        val file = try {
            withContext(ioDispatcher) { copy() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (file == null) {
            MetadataLookupState(auto = true, searched = true)
        } else {
            try {
                suggestionState(runSuggest(file, format, displayName))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + ioDispatcher) { file.delete() }
            }
        }
    }

    /** Edit sheet for a book with missing details: same suggestion, straight from the stored file. */
    fun suggestForBook(book: Book) = launchLookup(auto = true) {
        suggestionState(runSuggest(File(book.filePath), book.format, book.fileName))
    }

    private suspend fun runSuggest(file: File, format: String, displayName: String): Suggestion =
        try {
            suggestFn(file, format, displayName).also {
                Log.d("BookViewModel", "Suggestion for $displayName: source=${it.source} title=${it.title} author=${it.author} candidates=${it.candidates.size} queries=${it.queriesTried} unavailable=${it.unavailable}")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("BookViewModel", "Suggestion failed for $displayName", e)
            Suggestion(title = null, author = null, source = Suggestion.Source.FILENAME, unavailable = true, message = e.message)
        }

    private suspend fun suggestionState(s: Suggestion): MetadataLookupState {
        val coverPath = s.coverBytes?.takeIf { it.isNotEmpty() }?.let { bytes ->
            withContext(ioDispatcher) {
                try {
                    val dir = coversDir().apply { mkdirs() }
                    File(dir, "pending_${System.currentTimeMillis()}.jpg").also { it.writeBytes(bytes) }.absolutePath
                } catch (_: IOException) {
                    null
                }
            }
        }
        return MetadataLookupState(
            candidates = s.candidates, best = s.best, auto = true, offlineNote = s.offline,
            searched = true, suggestion = s, embeddedCoverPath = coverPath,
        )
    }

    private fun startLookup(title: String, author: String, auto: Boolean) = launchLookup(auto) {
        val result = try {
            lookupFn(title, author)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            MetadataLookupResult.Unavailable(offline = false, message = e.message.orEmpty())
        }
        lookupStateFor(result, auto)
    }

    private fun launchLookup(auto: Boolean, block: suspend () -> MetadataLookupState) {
        lookupJob?.cancel()
        val keepCover = _metadataLookupState.value.embeddedCoverPath
        _metadataLookupState.value = MetadataLookupState(isSearching = true, auto = auto, embeddedCoverPath = keepCover)
        lookupJob = viewModelScope.launch {
            val state = block()
            _metadataLookupState.value =
                if (state.embeddedCoverPath == null) state.copy(embeddedCoverPath = keepCover) else state
        }
    }

    fun clearMetadataLookup() {
        lookupJob?.cancel()
        _metadataLookupState.value = MetadataLookupState()
    }

    /** Saves edits to the database and into the file itself; the library card updates from Room. */
    fun updateBookMetadata(
        book: Book,
        title: String,
        author: String,
        coverUri: Uri? = null,
        coverUrl: String? = null,
    ) {
        viewModelScope.launch {
            try {
                val coverPath = resolveCover(coverUri, coverUrl, "metadata_${book.uid}") ?: book.coverPath
                bookRepository.applyMetadata(book, title, author, coverPath)
                _conversionError.value = null
            } catch (error: Exception) {
                _conversionError.value = error.localizedMessage ?: "Could not update book details"
            }
        }
    }

    fun deleteBook(book: Book) = deleteBooks(listOf(book))

    /** Deletes [books] except those a running transfer is sending; those are reported, not removed. */
    fun deleteBooks(books: List<Book>) {
        val busy = _sendingIds.value
        val (blocked, deletable) = books.partition { it.uid in busy }
        if (blocked.isNotEmpty()) {
            _events.trySend(LibraryEvent.Message(
                if (blocked.size == 1) "Can't delete a book while it is being sent."
                else "Can't delete books while they are being sent."
            ))
        }
        if (deletable.isEmpty()) return
        viewModelScope.launch {
            val ids = deletable.map { it.uid }.toSet()
            _transferQueue.update { queue -> queue.filter { it.uid !in ids } }
            pendingSend = pendingSend.filter { it.uid !in ids }
            _failedIds.update { it - ids }
            deletable.forEach { bookRepository.deleteBook(it) }
        }
    }

    /** "Remove original" after a format conversion: deletes the source row and its file. */
    fun removeOriginal(bookId: Int) {
        viewModelScope.launch {
            bookRepository.getBookById(bookId)?.let { deleteBooks(listOf(it)) }
        }
    }

    /**
     * Stores the chosen cover (a picked image or a downloaded match) once, as
     * `filesDir/covers/<prefix>_<ts>.jpg`. Returns null when there is none or it could not be fetched
     * (the user is told; the book is still saved).
     */
    private suspend fun resolveCover(coverUri: Uri?, coverUrl: String?, prefix: String = "pending"): String? {
        if (coverUri == null && coverUrl.isNullOrBlank()) return null
        val path = withContext(ioDispatcher) {
            val dir = coversDir().apply { mkdirs() }
            val file = File(dir, "${prefix}_${System.currentTimeMillis()}.jpg")
            try {
                val saved = if (coverUri != null) {
                    appContext.contentResolver.openInputStream(coverUri)?.use { input ->
                        file.outputStream().use(input::copyTo)
                        file.length() > 0
                    } ?: false
                } else {
                    fetchCoverFn(coverUrl!!)?.let { file.writeBytes(it); true } ?: false
                }
                if (saved) file.absolutePath else null.also { file.delete() }
            } catch (_: IOException) {
                file.delete()
                null
            }
        }
        if (path == null) _events.trySend(LibraryEvent.Message("Couldn't get that cover — saved without it"))
        return path
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
        passwordEdited = true
        _kindlePassword.value = value
        viewModelScope.launch(ioDispatcher) { kindleSettings.password = value }
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
            passwordLoad?.join()
            val passVal = _kindlePassword.value

            val result = sftpTestConnection(hostVal, portVal, userVal, passVal)

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

    /** Called once the user has seen a transfer result; a failed batch leaves the queue. */
    fun dismissTransferResult() {
        if (_transferState.value is TransferState.Error) clearQueue()
        _transferState.value = TransferState.Idle
    }

    /** Replaces the queue with [books] and starts sending them to the Kindle. */
    fun sendBooks(books: List<Book>) {
        if (books.isEmpty()) return
        if (_transferState.value is TransferState.Progress) {
            val known = pendingSend.map { it.uid }.toSet()
            pendingSend = pendingSend + books.filter { it.uid !in known }
            _events.trySend(LibraryEvent.Message("Will send after the current transfer"))
            return
        }
        _transferQueue.value = books
        transferBooksToKindle()
    }

    private fun sendPendingIfAny() {
        val next = pendingSend
        if (next.isEmpty()) return
        pendingSend = emptyList()
        // Books deleted in the meantime were already removed from pendingSend.
        _transferQueue.value = next
        transferBooksToKindle()
    }

    private fun failTransfer(error: TransferState.Error, books: List<Book>) {
        _sendingIds.value = emptySet()
        _failedIds.value = books.map { it.uid }.toSet()
        _transferState.value = error
        _events.trySend(LibraryEvent.TransferFailed(error, books))
        sendPendingIfAny()
    }

    fun transferBooksToKindle() {
        val booksToTransfer = _transferQueue.value
        if (booksToTransfer.isEmpty()) return
        if (_transferState.value is TransferState.Progress) return

        reloadSettings()
        if (_kindleIp.value.isBlank()) {
            _kindleStatus.value = KindleStatus.NotConfigured
            failTransfer(
                TransferState.Error(
                    "Your Kindle isn't set up yet. Add its address to send books.",
                    isConnectionProblem = true,
                ),
                booksToTransfer,
            )
            return
        }

        _sendingIds.value = booksToTransfer.map { it.uid }.toSet()
        _failedIds.update { it - _sendingIds.value }
        _transferState.value = TransferState.Progress(0, booksToTransfer.size, "Checking Kindle connection...")
        viewModelScope.launch {
            val hostVal = _kindleIp.value
            val portVal = _kindlePort.value.toIntOrNull() ?: 22
            val userVal = _kindleUsername.value
            passwordLoad?.join()
            val passVal = _kindlePassword.value
            val directoriesVal = _kindleDirectories.value
            val rescanVal = _triggerRescan.value

            _transferState.value = TransferState.Progress(0, booksToTransfer.size, "Checking Kindle connection...")
            val connection = sftpTestConnection(hostVal, portVal, userVal, passVal)
            if (connection.isFailure) {
                val detail = connection.exceptionOrNull()?.localizedMessage ?: "unknown error"
                _kindleStatus.value = KindleStatus.Offline
                failTransfer(
                    TransferState.Error(
                        "Can't reach your Kindle at $hostVal:$portVal. Check that it is on the same network and SSH is running. $detail",
                        isConnectionProblem = true,
                    ),
                    booksToTransfer,
                )
                return@launch
            }

            // Decoding and scaling covers is CPU/IO heavy; keep it off the main thread.
            val thumbs = withContext(defaultDispatcher) { prepareThumbnails(booksToTransfer) }

            val result = sftpTransfer(
                booksToTransfer, hostVal, portVal, userVal, passVal, directoriesVal, rescanVal, thumbs,
            ) { current, total, message ->
                _transferState.value = TransferState.Progress(current, total, message)
            }

            if (result.isSuccess) {
                _kindleStatus.value = KindleStatus.Online
                bookRepository.markTransferredBookIds(
                    booksToTransfer.map { it.uid }
                )
                clearQueue()
                _sendingIds.value = emptySet()
                _failedIds.value = emptySet()
                _transferState.value = TransferState.Idle
                _events.trySend(LibraryEvent.TransferSucceeded)
                sendPendingIfAny()
            } else {
                val errorMsg = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                failTransfer(TransferState.Error(errorMsg), booksToTransfer)
            }
        }
    }

    private fun prepareThumbnails(books: List<Book>): Map<Int, ByteArray> {
        val thumbs = mutableMapOf<Int, ByteArray>()
        for (book in books) {
            book.coverPath?.let { path ->
                try {
                    if (File(path).exists()) {
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
        return thumbs
    }
}

internal const val MSG_NO_MATCHES = "No matches found. Check the title or author."
internal const val MSG_OFFLINE = "You're offline — add details by hand or try again when connected."
internal const val MSG_BUSY = "Book search is busy right now. Try again in a minute."

/** Maps a catalog result to what the sheets show. Automatic lookups stay quiet when there is nothing to say. */
internal fun lookupStateFor(result: MetadataLookupResult, auto: Boolean): BookViewModel.MetadataLookupState =
    when (result) {
        is MetadataLookupResult.Matches -> BookViewModel.MetadataLookupState(
            candidates = result.candidates, best = result.best, auto = auto, searched = true,
        )
        MetadataLookupResult.NoMatches -> BookViewModel.MetadataLookupState(
            error = if (auto) null else MSG_NO_MATCHES, auto = auto, searched = true,
        )
        is MetadataLookupResult.Unavailable -> when {
            result.offline && auto -> BookViewModel.MetadataLookupState(offlineNote = true, auto = true, searched = true)
            result.offline -> BookViewModel.MetadataLookupState(error = MSG_OFFLINE, auto = auto, searched = true)
            else -> BookViewModel.MetadataLookupState(error = MSG_BUSY, auto = auto, searched = true)
        }
    }

/** True when an online lookup is likely to improve this title/author (file-name titles, no real author). */
internal fun shouldAutoLookup(title: String, author: String?): Boolean =
    title.isNotBlank() && (author.isNullOrBlank() || BookNameCleaner.isMissingMetadata(title, author))

/** Result of the quick reachability probe shown as the status chip in the library. */
enum class KindleStatus { NotConfigured, Checking, Online, Offline }

private const val KINDLE_PROBE_TIMEOUT_MS = 2000

private suspend fun canConnectTcp(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
    try {
        Socket().use { it.connect(InetSocketAddress(host, port), KINDLE_PROBE_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }
}

sealed interface LibraryUiState {
    object Loading : LibraryUiState
    data class Error(val throwable: Throwable) : LibraryUiState
    data class Success(val data: List<Book>) : LibraryUiState
}
