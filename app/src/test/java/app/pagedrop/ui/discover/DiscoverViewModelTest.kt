package app.pagedrop.ui.discover

import android.content.Context
import android.net.Uri
import app.pagedrop.data.BookRepository
import app.pagedrop.data.NullSharedPreferences
import app.pagedrop.data.SendFormatSetting
import app.pagedrop.data.SendFormatSettings
import app.pagedrop.data.local.database.Book
import app.pagedrop.discover.BookFormat
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.DownloadOption
import app.pagedrop.discover.SearchResults
import app.pagedrop.discover.SourceError
import app.pagedrop.discover.SourceKind
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoverViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val azw3 = DownloadOption(BookFormat.AZW3, "https://x/a.azw3")
    private val epub = DownloadOption(BookFormat.EPUB, "https://x/a.epub")

    private fun result(
        id: String = "gutenberg:1",
        title: String = "Pride and Prejudice",
        author: String = "Austen, Jane",
        options: List<DownloadOption> = listOf(epub, azw3),
        needsResolve: Boolean = false,
    ) = BookResult(
        id = id, source = SourceKind.GUTENBERG, sourceName = "Project Gutenberg", title = title,
        author = author, options = options, needsResolve = needsResolve,
    )

    private class FakeFinder(var results: SearchResults? = null) : BookFinder {
        var error: Exception? = null
        var resolved: BookResult? = null
        var resolveError: Exception? = null
        var sources = true
        override suspend fun search(query: String): SearchResults {
            error?.let { throw it }
            return results ?: SearchResults(query, emptyList(), emptyList())
        }
        override suspend fun resolve(result: BookResult): BookResult {
            resolveError?.let { throw it }
            return resolved ?: result
        }
        override fun bestOption(result: BookResult): DownloadOption? =
            result.options.minByOrNull { it.format.ordinal }
        override fun hasEnabledSources() = sources
    }

    private class FakeFetcher : BookFetcher {
        var failure: Exception? = null
        var calls = 0
        val progress = mutableListOf<Float?>()
        override suspend fun download(result: BookResult, option: DownloadOption, onProgress: (Float?) -> Unit): File {
            calls++
            failure?.let { throw it }
            onProgress(0.5f)
            return File("/tmp/${result.title}.${option.format.name.lowercase()}")
        }
    }

    private class FakeRecent : RecentSearches {
        private val state = MutableStateFlow<List<String>>(emptyList())
        override val items: StateFlow<List<String>> = state.asStateFlow()
        override fun add(query: String) { state.value = withRecent(state.value, query) }
        override fun clear() { state.value = emptyList() }
    }

    private class FakeRepo(initial: List<Book> = emptyList()) : BookRepository {
        val books = MutableStateFlow(initial)
        var converted = 0
        var convertFails = false
        var nextFormat = "AZW3"
        override fun getBooks(): Flow<List<Book>> = books
        override suspend fun importDownloaded(file: File, title: String, author: String, coverUrl: String?, coverBytes: ByteArray?): Book {
            val format = nextFormat
            return Book(uid = 10, title = title, author = author, fileName = file.name, filePath = file.path, format = format, fileSize = 1L)
                .also { books.value = books.value + it }
        }
        override suspend fun convertImported(book: Book): Book {
            if (convertFails) throw IOException("boom")
            converted++
            return book.copy(format = "MOBI")
        }
        override suspend fun addBook(context: Context, uri: Uri): Book = error("unused")
        override suspend fun addBookFromFile(book: Book): Long = 0
        override suspend fun convertAndAddBook(context: Context, uri: Uri, outputTitle: String?): Book = error("unused")
        override suspend fun deleteBook(book: Book) {}
        override suspend fun getBookById(id: Int): Book? = null
        override suspend fun markTransferred(uid: Int) {}
        override suspend fun markTransferredBookIds(uids: List<Int>) {}
        override suspend fun updateBookMetadata(book: Book, title: String, author: String, coverPath: String?): Book = book
    }

    private val created = mutableListOf<DiscoverViewModel>()
    private val finder = FakeFinder()
    private val fetcher = FakeFetcher()
    private val recent = FakeRecent()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private val sendFormat = SendFormatSettings(NullSharedPreferences())

    private fun vm(repo: FakeRepo = FakeRepo()) =
        DiscoverViewModel(finder, fetcher, repo, recent, sendFormat).also { created += it }

    @Test
    fun search_publishesResultsAndRemembersQuery() = runTest(dispatcher) {
        finder.results = SearchResults("austen", listOf(result()), listOf(SourceError("Internet Archive", true, "offline")))
        val vm = vm()
        vm.runSearch("  austen ")
        assertTrue(vm.search.value.searching)
        advanceUntilIdle()
        assertFalse(vm.search.value.searching)
        assertEquals(1, vm.search.value.results?.results?.size)
        assertEquals(1, vm.search.value.results?.errors?.size)
        assertEquals(listOf("austen"), vm.recent.value)
    }

    @Test
    fun recentSearches_dedupeAndCapAtEight() {
        var list = emptyList<String>()
        (1..10).forEach { list = withRecent(list, "q$it") }
        list = withRecent(list, "Q9")
        assertEquals(8, list.size)
        assertEquals("Q9", list.first())
        assertEquals(1, list.count { it.equals("q9", ignoreCase = true) })
    }

    @Test
    fun search_failureIsPlainLanguage() = runTest(dispatcher) {
        finder.error = java.net.UnknownHostException("x")
        val vm = vm()
        vm.runSearch("anything")
        advanceUntilIdle()
        assertTrue(vm.search.value.failure!!.contains("internet"))
    }

    @Test
    fun blankSearch_clears() = runTest(dispatcher) {
        val vm = vm()
        vm.runSearch("   ")
        advanceUntilIdle()
        assertNull(vm.search.value.results)
        assertTrue(vm.recent.value.isEmpty())
    }

    @Test
    fun select_defaultsToBestOption() = runTest(dispatcher) {
        val vm = vm()
        vm.select(result())
        assertEquals(azw3, vm.chosenOption.value)
        vm.chooseOption(epub)
        assertEquals(epub, vm.chosenOption.value)
    }

    @Test
    fun select_resolvesWhenNeeded() = runTest(dispatcher) {
        finder.resolved = result(options = listOf(epub), needsResolve = false)
        val vm = vm()
        vm.select(result(options = emptyList(), needsResolve = true))
        advanceUntilIdle()
        assertEquals(epub, vm.chosenOption.value)
        assertEquals(ActionState.Idle, vm.action.value)
        assertEquals(1, vm.selected.value?.options?.size)
    }

    @Test
    fun sendKindleNative_downloadsImportsAndFinishesWithoutConverting() = runTest(dispatcher) {
        val repo = FakeRepo()
        val vm = vm(repo)
        vm.select(result())
        vm.sendToKindle()
        advanceUntilIdle()
        val done = vm.action.value as ActionState.Finished
        assertTrue(done.sendRequested)
        assertEquals("AZW3", done.book.format)
        assertEquals(0, repo.converted)
    }

    @Test
    fun sendEpub_convertsBeforeSend() = runTest(dispatcher) {
        val repo = FakeRepo().apply { nextFormat = "EPUB" }
        val vm = vm(repo)
        vm.select(result(options = listOf(epub)))
        vm.sendToKindle()
        advanceUntilIdle()
        val done = vm.action.value as ActionState.Finished
        assertEquals("MOBI", done.book.format)
        assertEquals(1, repo.converted)
    }

    @Test
    fun sendEpub_withKeepEpubSetting_skipsConversionAndSendsAsEpub() = runTest(dispatcher) {
        sendFormat.set(SendFormatSetting.KEEP_ORIGINAL)
        val repo = FakeRepo().apply { nextFormat = "EPUB" }
        val vm = vm(repo)
        vm.select(result(options = listOf(epub)))
        assertEquals(null, vm.keepOverride.value)
        vm.sendToKindle()
        advanceUntilIdle()
        val done = vm.action.value as ActionState.Finished
        assertTrue(done.sendRequested)
        assertEquals("EPUB", done.book.format)
        assertEquals(0, repo.converted)
    }

    @Test
    fun sendEpub_perBookKeepOverridesTheKindleFormatSetting() = runTest(dispatcher) {
        val repo = FakeRepo().apply { nextFormat = "EPUB" }
        val vm = vm(repo)
        vm.select(result(options = listOf(epub)))
        vm.setKeepOriginal(true)
        vm.sendToKindle()
        advanceUntilIdle()
        assertEquals("EPUB", (vm.action.value as ActionState.Finished).book.format)
        assertEquals(0, repo.converted)
    }

    @Test
    fun sendEpub_perBookConvertOverridesTheKeepEpubSetting() = runTest(dispatcher) {
        sendFormat.set(SendFormatSetting.KEEP_ORIGINAL)
        val repo = FakeRepo().apply { nextFormat = "EPUB" }
        val vm = vm(repo)
        vm.select(result(options = listOf(epub)))
        vm.setKeepOriginal(false)
        vm.sendToKindle()
        advanceUntilIdle()
        assertEquals("MOBI", (vm.action.value as ActionState.Finished).book.format)
        assertEquals(1, repo.converted)
    }

    private val txt = DownloadOption(BookFormat.TXT, "https://x/a.txt")
    private val pdf = DownloadOption(BookFormat.PDF, "https://x/a.pdf")

    @Test
    fun sendPdf_followsTheSettingLikeAnyFormat() = runTest(dispatcher) {
        val repo = FakeRepo().apply { nextFormat = "PDF" }
        val vm = vm(repo)
        vm.select(result(options = listOf(pdf)))
        vm.sendToKindle()
        advanceUntilIdle()
        assertEquals(1, repo.converted)

        val repo2 = FakeRepo().apply { nextFormat = "PDF" }
        val vm2 = vm(repo2)
        vm2.select(result(options = listOf(pdf)))
        vm2.setKeepOriginal(true)
        vm2.sendToKindle()
        advanceUntilIdle()
        assertEquals("PDF", (vm2.action.value as ActionState.Finished).book.format)
        assertEquals(0, repo2.converted)
    }

    @Test
    fun sendTxt_followsTheSetting() = runTest(dispatcher) {
        val repo = FakeRepo().apply { nextFormat = "TXT" }
        val vm = vm(repo)
        vm.select(result(options = listOf(txt)))
        vm.sendToKindle()
        advanceUntilIdle()
        assertEquals(1, repo.converted)

        sendFormat.set(SendFormatSetting.KEEP_ORIGINAL)
        val repo2 = FakeRepo().apply { nextFormat = "TXT" }
        val vm2 = vm(repo2)
        vm2.select(result(options = listOf(txt)))
        vm2.sendToKindle()
        advanceUntilIdle()
        assertEquals("TXT", (vm2.action.value as ActionState.Finished).book.format)
        assertEquals(0, repo2.converted)
    }

    @Test
    fun keepOverride_resetsWhenAnotherBookIsSelected() = runTest(dispatcher) {
        val vm = vm()
        vm.select(result(options = listOf(epub)))
        vm.setKeepOriginal(true)
        assertEquals(true, vm.keepOverride.value)
        vm.dismissSelected()
        vm.select(result(id = "gutenberg:2", options = listOf(epub)))
        assertNull(vm.keepOverride.value)
    }

    @Test
    fun addToLibrary_keepsEpubAndDoesNotRequestSend() = runTest(dispatcher) {
        val repo = FakeRepo().apply { nextFormat = "EPUB" }
        val vm = vm(repo)
        vm.select(result(options = listOf(epub)))
        vm.addToLibrary()
        advanceUntilIdle()
        val done = vm.action.value as ActionState.Finished
        assertFalse(done.sendRequested)
        assertEquals("EPUB", done.book.format)
        assertEquals(0, repo.converted)
    }

    @Test
    fun downloadFailure_thenRetrySucceeds() = runTest(dispatcher) {
        val vm = vm()
        fetcher.failure = IOException("The download was interrupted.")
        vm.select(result())
        vm.sendToKindle()
        advanceUntilIdle()
        assertTrue(vm.action.value is ActionState.Failed)
        fetcher.failure = null
        vm.retry()
        advanceUntilIdle()
        assertTrue((vm.action.value as ActionState.Finished).sendRequested)
        assertEquals(2, fetcher.calls)
    }

    @Test
    fun convertFailure_retryDoesNotRedownload() = runTest(dispatcher) {
        val repo = FakeRepo().apply { nextFormat = "EPUB"; convertFails = true }
        val vm = vm(repo)
        vm.select(result(options = listOf(epub)))
        vm.sendToKindle()
        advanceUntilIdle()
        val failed = vm.action.value as ActionState.Failed
        assertTrue(failed.message.contains("convert"))
        repo.convertFails = false
        vm.retry()
        advanceUntilIdle()
        assertTrue(vm.action.value is ActionState.Finished)
        assertEquals(1, fetcher.calls)
    }

    @Test
    fun inLibrary_isDetectedAndSendAgainSkipsDownload() = runTest(dispatcher) {
        val existing = Book(uid = 3, title = "Pride & Prejudice!", author = "Jane Austen", fileName = "p.azw3", filePath = "/p.azw3", format = "AZW3", fileSize = 1)
        val repo = FakeRepo(listOf(existing))
        val vm = vm(repo)
        advanceUntilIdle()
        // "Austen, Jane" vs "Jane Austen" differ; same-title unknown-author matching is covered below.
        assertNull(vm.libraryMatch(result()))
        val r = result(author = "Jane Austen", title = "Pride and Prejudice")
        assertNull(vm.libraryMatch(r)) // "&" is not "and"
        val same = result(author = "jane  austen", title = "Pride & Prejudice")
        assertNotNull(vm.libraryMatch(same))
        vm.select(same)
        vm.sendToKindle()
        advanceUntilIdle()
        assertEquals(0, fetcher.calls)
        assertEquals(3, (vm.action.value as ActionState.Finished).book.uid)
    }

    @Test
    fun unknownAuthor_matchesOnTitleAlone() {
        val book = Book(uid = 1, title = "Dracula", author = "Unknown Author", fileName = "d", filePath = "/d", format = "PDF", fileSize = 1)
        assertTrue(matchesLibraryBook(result(title = "Dracula", author = "Bram Stoker"), book))
        assertFalse(matchesLibraryBook(result(title = "Carmilla", author = "Bram Stoker"), book))
    }

    @Test
    fun hindiLibraryMatchesPreserveVowelMarks() {
        val book = Book(uid = 1, title = "दिन", author = "कवि", fileName = "d", filePath = "/d", format = "EPUB", fileSize = 1)
        assertTrue(matchesLibraryBook(result(title = "दिन", author = "कवि"), book))
        assertFalse(matchesLibraryBook(result(title = "दीन", author = "कवि"), book))
    }

    @Test
    fun retryAfterResolutionFailureShowsFormatsBeforeDownloading() = runTest(dispatcher) {
        val vm = vm()
        val pending = result(options = emptyList(), needsResolve = true)
        finder.resolveError = IOException("Temporarily unavailable")
        vm.select(pending)
        advanceUntilIdle()
        assertTrue(vm.action.value is ActionState.Failed)
        finder.resolveError = null
        finder.resolved = pending.copy(needsResolve = false, options = listOf(epub))
        vm.retry()
        advanceUntilIdle()
        assertEquals(ActionState.Idle, vm.action.value)
        assertEquals(epub, vm.chosenOption.value)
        assertEquals(0, fetcher.calls)
    }

    @Test
    fun dismissWhileWorking_cancelsAndClears() = runTest(dispatcher) {
        val vm = vm()
        vm.select(result())
        vm.sendToKindle()
        vm.dismissSelected()
        advanceUntilIdle()
        assertNull(vm.selected.value)
        assertEquals(ActionState.Idle, vm.action.value)
    }

    @Test
    fun browserDownloadReviewsMetadataAndUsesExistingConvertSendFlow() = runTest(dispatcher) {
        val session = app.pagedrop.discover.BrowserDownloadSession("UA", "https://site.example/book", { null })
        val browser = app.pagedrop.discover.BrowserDownloads.capture(
            "https://files.example/गोदान.epub", null, null, "Library", session,
        )!!
        val repo = FakeRepo().apply { nextFormat = "EPUB" }
        val vm = vm(repo)
        vm.select(browser)
        vm.editBrowserMetadata("गोदान", "प्रेमचंद")
        assertSame(session, vm.chosenOption.value!!.browserSession)
        vm.sendToKindle()
        advanceUntilIdle()
        val done = vm.action.value as ActionState.Finished
        assertEquals("गोदान", done.book.title)
        assertEquals("प्रेमचंद", done.book.author)
        assertEquals("MOBI", done.book.format)
        assertTrue(done.sendRequested)
        assertEquals(1, repo.converted)
    }

    @Test
    fun browserFilenameGuessDoesNotReuseDifferentExistingEdition() = runTest(dispatcher) {
        val existing = Book(uid = 3, title = "Frankenstein", author = "Unknown Author", fileName = "old.epub", filePath = "/old.epub", format = "EPUB", fileSize = 1)
        val repo = FakeRepo(listOf(existing))
        val vm = vm(repo)
        advanceUntilIdle()
        vm.select(result(title = "Frankenstein", author = "").copy(source = SourceKind.WEBSITE))
        vm.addToLibrary()
        advanceUntilIdle()
        assertEquals(1, fetcher.calls)
        assertEquals(10, (vm.action.value as ActionState.Finished).book.uid)
    }

    @Test
    fun chipLabels() {
        assertEquals("Kindle-ready", formatChipLabel(BookFormat.AZW3))
        assertEquals("Kindle-ready", formatChipLabel(BookFormat.MOBI))
        assertEquals("EPUB · will convert", formatChipLabel(BookFormat.EPUB))
        assertEquals("PDF", formatChipLabel(BookFormat.PDF))
        assertNull(formatChipLabel(null))
    }
}
