package app.pagedrop.ui.tools.dashboard

import app.pagedrop.metadata.Suggestion
import app.pagedrop.metadata.MetadataSuggester
import android.content.Context
import androidx.lifecycle.ViewModel
import app.pagedrop.data.BookRepository
import app.pagedrop.metadata.BookNameCleaner
import app.pagedrop.metadata.EmbeddedMetadataReader
import app.pagedrop.metadata.MetadataJunk
import app.pagedrop.tools.dashboard.KindleDelete
import app.pagedrop.transfer.sftp.KindleSftpClient
import app.pagedrop.ui.kindle.KindleBookGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.File
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.KindleSettings
import app.pagedrop.data.local.database.BookDao
import app.pagedrop.ui.kindle.KindleLibraryMatcher
import app.pagedrop.ui.kindle.KindleLibraryReport
import kotlinx.coroutines.flow.first
import app.pagedrop.tools.dashboard.DeviceCommandRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val kindleSettings: KindleSettings,
    private val bookDao: BookDao,
    private val bookRepository: BookRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class DashboardState(
        val isLoading: Boolean = false,
        val deviceStatus: DeviceCommandRunner.DeviceStatus? = null,
        val lastResult: DeviceCommandRunner.CommandResult? = null,
        val error: String? = null,
        /** null = not checked yet, true = answered on the SSH port, false = could not connect. */
        val reachable: Boolean? = null,
        val isConfigured: Boolean = true,
        /** The real exception from the TCP probe when [reachable] is false. */
        val probeError: Throwable? = null,
        /** True while "Check Kindle library" is running. */
        val isChecking: Boolean = false,
        val libraryReport: KindleLibraryReport? = null,
        /** Real failure (unreachable / auth) from the last library check. */
        val libraryError: Throwable? = null,
        /** True while books are being deleted from the Kindle. */
        val isDeleting: Boolean = false,
        /** Group key of the book being copied into the PageDrop library. */
        val savingKey: String? = null,
        /** Group keys saved to the library this session (shown as "In PageDrop"). */
        val savedKeys: Set<String> = emptySet()
    ) {
        val isBusy: Boolean get() = isChecking || isDeleting || savingKey != null
    }

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    val host: String get() = kindleSettings.host
    val port: Int get() = kindleSettings.port

    val repairRecipes = DeviceCommandRunner.repairRecipes

    fun refresh() {
        viewModelScope.launch {
            if (!kindleSettings.isConfigured) {
                _state.update { DashboardState(isConfigured = false) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null, lastResult = null, isConfigured = true) }
            val probeError: Throwable? = withContext(Dispatchers.IO) {
                try {
                    Socket().use { it.connect(InetSocketAddress(kindleSettings.host, kindleSettings.port), 2_000) }
                    null
                } catch (e: Exception) {
                    e
                }
            }
            if (probeError != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        reachable = false,
                        probeError = probeError,
                        deviceStatus = null,
                        error = "Can't reach ${kindleSettings.host}:${kindleSettings.port}."
                    )
                }
                return@launch
            }
            try {
                val status = DeviceCommandRunner.getDeviceStatus(
                    host = kindleSettings.host,
                    port = kindleSettings.port,
                    user = kindleSettings.username,
                    pass = kindleSettings.password
                )
                _state.update { it.copy(isLoading = false, reachable = true, probeError = null, deviceStatus = status) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        reachable = true,
                        error = "Failed to fetch device status: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    /** One-shot plain-words results for a snackbar. */
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    fun triggerRescan() {
        if (_state.value.isBusy) return
        viewModelScope.launch { runLibraryCheck() }
    }

    /** Rescans the Kindle and reconciles On Kindle state; true when the check worked. */
    private suspend fun runLibraryCheck(): Boolean {
        _state.update { it.copy(isChecking = true, libraryError = null) }
        val result = DeviceCommandRunner.refreshKindleLibrary(
            kindleSettings.host, kindleSettings.port,
            kindleSettings.username, kindleSettings.password
        )
        val scan = result.getOrNull()
        if (scan == null) {
            _state.update { it.copy(isChecking = false, libraryError = result.exceptionOrNull()) }
            return false
        }
        val report = try {
            val books = bookDao.getBooks().first()
            val matches = KindleLibraryMatcher.matchBooks(books, scan.files)
            val changes = KindleLibraryMatcher.reconcile(books, matches, System.currentTimeMillis())
            if (changes.markTransferred.isNotEmpty()) bookDao.markTransferred(changes.markTransferred, changes.timestamp)
            if (changes.clearTransferred.isNotEmpty()) bookDao.clearTransferred(changes.clearTransferred)
            KindleLibraryMatcher.report(scan, matches)
        } catch (e: Exception) {
            KindleLibraryMatcher.report(scan, emptyMap())
        }
        _state.update { it.copy(isChecking = false, libraryReport = report, libraryError = null) }
        return true
    }

    /**
     * Deletes the chosen copies of [groups] from the Kindle, then re-checks the library.
     * [alsoSidecars] also removes the .sdr folders (highlights and reading progress).
     */
    fun deleteFromKindle(groups: List<KindleBookGroup>, home: Boolean, koreader: Boolean, alsoSidecars: Boolean) {
        val current = _state.value
        val report = current.libraryReport ?: return
        if (current.isBusy) return
        val chosen = groups.flatMap { g ->
            (if (home) g.homeFiles else emptyList()) + (if (koreader) g.koreaderFiles else emptyList())
        }
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isDeleting = true) }
            val chosenPaths = chosen.map { it.path }
            // A PageDrop book loses its Kindle thumbnail only when no copy of it stays on the Kindle.
            val thumbnailIds = groups.filter { g -> g.files.all { it.path in chosenPaths } }
                .mapNotNull { it.libraryBookId }
                .mapNotNull { uid -> runCatching { bookDao.getBookById(uid) }.getOrNull() }
                .map { it.kindleUuid ?: it.uid.toString() }
            val result = DeviceCommandRunner.deleteFromKindle(
                kindleSettings.host, kindleSettings.port, kindleSettings.username, kindleSettings.password,
                paths = chosenPaths,
                deleteSidecars = alsoSidecars,
                allowedPaths = report.files.map { it.path }.toSet(),
                thumbnailIds = thumbnailIds
            )
            _state.update { it.copy(isDeleting = false) }
            val outcome = result.getOrNull()
            if (outcome == null) {
                val why = result.exceptionOrNull()?.message
                _messages.send("Couldn't delete from your Kindle. " + (why ?: "Check the connection and try again."))
                return@launch
            }
            runLibraryCheck()
            _messages.send(
                when {
                    outcome.allOk -> "Deleted from your Kindle"
                    outcome.deleted.isEmpty() -> "Couldn't delete from your Kindle. Nothing was removed."
                    else -> {
                        val names = outcome.failed.map { it.substringAfterLast('/') }
                        "Deleted ${outcome.deleted.size} of ${chosenPaths.distinct().size}. " +
                            if (names.isNotEmpty()) "Couldn't delete: ${names.joinToString()}" else "Some reading progress folders stayed."
                    }
                }
            )
        }
    }

    /** Cover lookup for books saved from the Kindle; a seam for tests. */
    internal var suggestCover: suspend (File, String, String) -> Suggestion =
        { file, format, name -> MetadataSuggester.suggest(file, format, name) }

    /** Downloads one Kindle book into the PageDrop library. */
    fun saveToLibrary(group: KindleBookGroup) {
        val current = _state.value
        val report = current.libraryReport ?: return
        if (current.isBusy || group.inLibrary) return
        val file = (group.homeFiles + group.koreaderFiles).firstOrNull { f -> report.files.any { it.path == f.path } } ?: return
        viewModelScope.launch {
            _state.update { it.copy(savingKey = group.key) }
            val dir = File(appContext.cacheDir, "kindle-import").apply { mkdirs() }
            val temp = File(dir, file.name)
            val downloaded = KindleSftpClient.downloadFile(
                kindleSettings.host, kindleSettings.port, kindleSettings.username, kindleSettings.password,
                file.path, temp
            )
            val local = downloaded.getOrNull()
            if (local == null) {
                _state.update { it.copy(savingKey = null) }
                _messages.send("Couldn't copy the book from your Kindle. Check the connection and try again.")
                return@launch
            }
            try {
                val (title, author) = withContext(Dispatchers.IO) { guessTitleAuthor(local) }
                // Cover: the one inside the file first, else an online cover when the lookup is confident.
                val suggestion = withContext(Dispatchers.IO) {
                    runCatching { suggestCover(local, local.extension.uppercase(), file.name) }.getOrNull()
                }
                bookRepository.importDownloaded(
                    local, title, author,
                    coverUrl = suggestion?.coverUrl?.takeIf { suggestion.best != null },
                    coverBytes = suggestion?.coverBytes,
                )
                runCatching {
                    val books = bookDao.getBooks().first()
                    val matches = KindleLibraryMatcher.matchBooks(books, report.files)
                    val updated = KindleLibraryMatcher.report(
                        DeviceCommandRunner.KindleLibraryScan(report.rescanMethod, report.koreaderRunning, report.files), matches
                    )
                    _state.update { it.copy(libraryReport = updated) }
                }
                _state.update { it.copy(savingKey = null, savedKeys = it.savedKeys + group.key) }
                _messages.send("Saved to your library")
            } catch (e: Exception) {
                local.delete()
                _state.update { it.copy(savingKey = null) }
                _messages.send("Couldn't add the book to your library.")
            }
        }
    }

    private fun guessTitleAuthor(file: File): Pair<String, String> {
        val format = file.extension.uppercase()
        val embedded = runCatching { EmbeddedMetadataReader.read(file, format) }.getOrNull()
        val parsed = BookNameCleaner.parse(file.nameWithoutExtension)
        val title = embedded?.title?.takeIf { MetadataJunk.isUsableTitle(it) }
            ?: parsed.title.ifBlank { file.nameWithoutExtension }
        val author = embedded?.author?.takeIf { MetadataJunk.isUsableAuthor(it) } ?: parsed.author.orEmpty()
        return title to author
    }

    fun reboot() {
        viewModelScope.launch {
            DeviceCommandRunner.rebootDevice(
                kindleSettings.host, kindleSettings.port,
                kindleSettings.username, kindleSettings.password
            )
        }
    }

    fun runRepair(recipe: DeviceCommandRunner.RepairRecipe) {
        viewModelScope.launch {
            val result = DeviceCommandRunner.runRepair(
                recipe = recipe,
                host = kindleSettings.host,
                port = kindleSettings.port,
                user = kindleSettings.username,
                pass = kindleSettings.password
            )
            _state.update { it.copy(lastResult = result, error = result.stderr.ifBlank { null }) }
        }
    }
}
