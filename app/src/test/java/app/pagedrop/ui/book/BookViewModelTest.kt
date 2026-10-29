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
import android.content.ContextWrapper
import android.net.Uri
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import android.net.TestUri
import app.pagedrop.share.PendingSharedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.pagedrop.data.BookRepository
import app.pagedrop.data.KindleSettings
import app.pagedrop.data.NullSharedPreferences
import app.pagedrop.data.SendFormatSettings
import app.pagedrop.data.local.database.Book
import app.pagedrop.metadata.BookMetadataCandidate
import app.pagedrop.metadata.MetadataLookupResult
import app.pagedrop.metadata.Suggestion
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    private val created = mutableListOf<BookViewModel>()

    /** Every ViewModel is built here so no coroutine (or real socket/IO) outlives its test. */
    private fun newVm(context: Context, repo: BookRepository, settings: KindleSettings): BookViewModel =
        BookViewModel(context, repo, settings, null, PendingSharedFile(), SendFormatSettings(NullSharedPreferences())).also {
            it.ioDispatcher = dispatcher
            it.defaultDispatcher = dispatcher
            it.sftpTestConnection = { _, _, _, _ -> Result.failure(java.io.IOException("no network in tests")) }
            it.sftpTransfer = { _, _, _, _, _, _, _, _, _ -> Result.failure(java.io.IOException("no network in tests")) }
            it.reachabilityCheck = { _, _ -> false }
            created += it
        }

    @After
    fun tearDown() {
        created.forEach { it.viewModelScope.cancel() }
        created.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun kindleStatus_notConfiguredWhenHostBlank() = runTest(dispatcher) {
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), KindleSettings())
        viewModel.refreshKindleStatus()
        advanceUntilIdle()
        assertEquals(KindleStatus.NotConfigured, viewModel.kindleStatus.value)
    }

    @Test
    fun kindleStatus_onlineWhenHostReachable() = runTest(dispatcher) {
        val settings = KindleSettings().apply { host = "192.168.1.50"; port = 2222 }
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), settings)
        var probed: Pair<String, Int>? = null
        viewModel.reachabilityCheck = { host, port -> probed = host to port; true }

        viewModel.refreshKindleStatus()
        assertEquals(KindleStatus.Checking, viewModel.kindleStatus.value)
        advanceUntilIdle()

        assertEquals(KindleStatus.Online, viewModel.kindleStatus.value)
        assertEquals("192.168.1.50" to 2222, probed)
    }

    @Test
    fun kindleStatus_offlineWhenHostUnreachable() = runTest(dispatcher) {
        val settings = KindleSettings().apply { host = "192.168.1.50" }
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), settings)
        viewModel.reachabilityCheck = { _, _ -> false }

        viewModel.refreshKindleStatus()
        advanceUntilIdle()

        assertEquals(KindleStatus.Offline, viewModel.kindleStatus.value)
    }

    @Test
    fun sendBooks_withoutKindleHost_reportsConnectionProblem() = runTest(dispatcher) {
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), KindleSettings())
        val book = Book(uid = 9, title = "T", author = "A", fileName = "f.mobi", filePath = "/p/f.mobi", format = "MOBI", fileSize = 1)

        viewModel.sendBooks(listOf(book))

        val state = viewModel.transferState.value
        assertTrue(state is BookViewModel.TransferState.Error && state.isConnectionProblem)
        assertEquals(KindleStatus.NotConfigured, viewModel.kindleStatus.value)

        viewModel.dismissTransferResult()
        assertEquals(BookViewModel.TransferState.Idle, viewModel.transferState.value)
        assertTrue(viewModel.transferQueue.value.isEmpty())
    }

    @Test
    fun deleteBooks_removesThemFromQueueAndRepository() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        val a = Book(uid = 1, title = "A", author = "x", fileName = "a", filePath = "/a", format = "MOBI", fileSize = 1)
        val b = Book(uid = 2, title = "B", author = "x", fileName = "b", filePath = "/b", format = "MOBI", fileSize = 1)
        repo.addBookToTest(a)
        repo.addBookToTest(b)
        viewModel.queueForTransfer(a)

        viewModel.deleteBooks(listOf(a, b))
        advanceUntilIdle()

        assertTrue(viewModel.transferQueue.value.isEmpty())
        assertEquals(null, repo.getBookById(1))
        assertEquals(null, repo.getBookById(2))
    }

    private fun book(id: Int) = Book(uid = id, title = "B$id", author = "x", fileName = "f$id", filePath = "/f$id", format = "MOBI", fileSize = 1)

    private fun busyViewModel(repo: FakeBookRepository): BookViewModel {
        val settings = KindleSettings().apply { host = "127.0.0.1" }
        return newVm(ContextWrapper(null), repo, settings)
    }

    @Test
    fun sendBooks_duringTransfer_isQueuedWithMessage() = runTest(dispatcher) {
        val viewModel = busyViewModel(FakeBookRepository())
        viewModel.sendBooks(listOf(book(1)))
        assertTrue(viewModel.transferState.value is BookViewModel.TransferState.Progress)

        viewModel.sendBooks(listOf(book(2)))

        // The running transfer is untouched and the user is told the second request is waiting.
        assertEquals(listOf(1), viewModel.transferQueue.value.map { it.uid })
        assertEquals(setOf(1), viewModel.sendingIds.value)
        assertEquals(
            BookViewModel.LibraryEvent.Message("Will send after the current transfer"),
            viewModel.events.first(),
        )
    }

    @Test
    fun deleteBooks_skipsBooksBeingSent() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = busyViewModel(repo)
        repo.addBookToTest(book(1))
        repo.addBookToTest(book(2))
        viewModel.sendBooks(listOf(book(1)))

        viewModel.deleteBooks(listOf(book(1), book(2)))
        advanceUntilIdle()

        assertNotNull(repo.getBookById(1))
        assertEquals(null, repo.getBookById(2))
        assertTrue(viewModel.events.first() is BookViewModel.LibraryEvent.Message)
    }

    @Test
    fun failedSend_emitsOneShotEventAndMarksFailedIds() = runTest(dispatcher) {
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), KindleSettings())
        viewModel.sendBooks(listOf(book(5)))

        assertEquals(setOf(5), viewModel.failedIds.value)
        assertTrue(viewModel.sendingIds.value.isEmpty())
        val event = viewModel.events.first()
        assertTrue(event is BookViewModel.LibraryEvent.TransferFailed && event.books.map { it.uid } == listOf(5))
    }

    @Test
    fun deletingFailedBook_clearsItsFailedMarker() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        repo.addBookToTest(book(5))
        viewModel.sendBooks(listOf(book(5)))
        viewModel.deleteBooks(listOf(book(5)))
        advanceUntilIdle()
        assertTrue(viewModel.failedIds.value.isEmpty())
    }

    @Test
    fun uiState_initiallyLoading() = runTest(dispatcher) {
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), KindleSettings())
        assertEquals(LibraryUiState.Loading, viewModel.uiState.first())
    }

    @Test
    fun transferQueue_toggleQueued_addsBook() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        val book = Book(uid = 1, title = "T", author = "A", fileName = "f.mobi", filePath = "/p/f.mobi", format = "MOBI", fileSize = 100)
        repo.addBookToTest(book)

        viewModel.toggleQueued(book)
        val queue = viewModel.transferQueue.first()
        assertEquals(1, queue.size)
        assertEquals(1, queue[0].uid)
    }

    @Test
    fun transferQueue_toggleQueued_removesBook() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        val book = Book(uid = 2, title = "T2", author = "A2", fileName = "f2.mobi", filePath = "/p/f2.mobi", format = "MOBI", fileSize = 200)
        repo.addBookToTest(book)
        viewModel.toggleQueued(book)
        viewModel.toggleQueued(book)
        val queue = viewModel.transferQueue.first()
        assertEquals(0, queue.size)
    }

    @Test
    fun isQueued_returnsTrueForQueuedBook() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        val book = Book(uid = 3, title = "T3", author = "A3", fileName = "f3.mobi", filePath = "/p/f3.mobi", format = "MOBI", fileSize = 300)
        repo.addBookToTest(book)
        viewModel.toggleQueued(book)
        assertTrue(viewModel.isQueued(book))
    }

    // ── Metadata lookup ──

    private val gatsby = BookMetadataCandidate(title = "The Great Gatsby", author = "F. Scott Fitzgerald", coverUrl = "https://c/x.jpg", confidence = 0.95f)

    @Test
    fun lookupMapping_noMatches_manualAndAuto() {
        val manual = lookupStateFor(MetadataLookupResult.NoMatches, auto = false)
        assertEquals("No matches found. Check the title or author.", manual.error)
        assertNull(lookupStateFor(MetadataLookupResult.NoMatches, auto = true).error)
    }

    @Test
    fun lookupMapping_unavailable() {
        val offline = MetadataLookupResult.Unavailable(offline = true, message = "x")
        assertEquals(
            "You're offline — add details by hand or try again when connected.",
            lookupStateFor(offline, auto = false).error,
        )
        val autoOffline = lookupStateFor(offline, auto = true)
        assertNull(autoOffline.error)
        assertTrue(autoOffline.offlineNote)

        val busy = lookupStateFor(MetadataLookupResult.Unavailable(offline = false, message = "429"), auto = false)
        assertEquals("Book search is busy right now. Try again in a minute.", busy.error)
        assertFalse(busy.error!!.contains("Google", ignoreCase = true))
    }

    @Test
    fun lookupMapping_matchesExposeCandidatesAndBest() {
        val weak = MetadataLookupResult.Matches(listOf(gatsby), best = null)
        assertNull(lookupStateFor(weak, auto = true).best)
        val strong = lookupStateFor(MetadataLookupResult.Matches(listOf(gatsby), best = gatsby), auto = true)
        assertEquals(gatsby, strong.best)
        assertEquals(listOf(gatsby), strong.candidates)
    }

    @Test
    fun autoLookupTrigger_onlyForMissingMetadata() {
        assertTrue(shouldAutoLookup("the-great-gatsby", ""))
        assertTrue(shouldAutoLookup("Dune", "Unknown Author"))
        assertTrue(shouldAutoLookup("Dune", null))
        assertFalse(shouldAutoLookup("Dune", "Frank Herbert"))
        assertFalse(shouldAutoLookup("", ""))
    }

    private fun suggestionVm(suggestion: Suggestion): Pair<BookViewModel, File> {
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), KindleSettings())
        val covers = java.nio.file.Files.createTempDirectory("covers").toFile().also { it.deleteOnExit() }
        viewModel.coversDir = { covers }
        viewModel.copyToCacheFn = { _, _ -> File.createTempFile("suggest", ".pdf") }
        viewModel.suggestFn = { _, _, _ -> suggestion }
        return viewModel to covers
    }

    @Test
    fun suggestForUri_exposesSuggestionAndDeletesTempFile() = runTest(dispatcher) {
        val embedded = Suggestion(title = "The Great Gatsby", author = "F. Scott Fitzgerald", source = Suggestion.Source.EMBEDDED, best = gatsby, candidates = listOf(gatsby))
        val (viewModel, _) = suggestionVm(embedded)
        var temp: File? = null
        val copy: suspend () -> File? = { File.createTempFile("suggest", ".pdf").also { temp = it } }
        var asked: Triple<String, String, String>? = null
        viewModel.suggestFn = { f, fmt, name -> asked = Triple(f.name.substringBefore("."), fmt, name); embedded }

        viewModel.suggestForCopy(copy, "greatgatsby_ebook_free.pdf", "PDF")
        assertTrue(viewModel.metadataLookupState.value.isSearching)
        advanceUntilIdle()

        val state = viewModel.metadataLookupState.value
        assertEquals(embedded, state.suggestion)
        assertTrue(state.auto && state.searched && !state.isSearching)
        assertEquals(gatsby, state.best)
        assertEquals("PDF", asked!!.second)
        assertEquals("greatgatsby_ebook_free.pdf", asked!!.third)
        assertFalse(temp!!.exists())
    }

    @Test
    fun suggestForUri_unreadableFileLeavesQuietEmptyState() = runTest(dispatcher) {
        val (viewModel, _) = suggestionVm(Suggestion(null, null, Suggestion.Source.FILENAME))
        viewModel.suggestForCopy({ null }, "x.pdf", "PDF")
        advanceUntilIdle()
        val state = viewModel.metadataLookupState.value
        assertNull(state.suggestion)
        assertTrue(state.searched && !state.isSearching)
        assertNull(state.error)
    }

    @Test
    fun suggestForUri_offlineSetsQuietNote() = runTest(dispatcher) {
        val (viewModel, _) = suggestionVm(
            Suggestion("Dune", null, Suggestion.Source.FILENAME, unavailable = true, offline = true),
        )
        viewModel.suggestForCopy({ File.createTempFile("suggest", ".epub") }, "dune.epub", "EPUB")
        advanceUntilIdle()
        val state = viewModel.metadataLookupState.value
        assertTrue(state.offlineNote)
        assertNull(state.error)
        assertEquals("Dune", state.suggestion!!.title)
    }

    @Test
    fun suggestForUri_embeddedCoverBytesSavedAsPendingCover() = runTest(dispatcher) {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val (viewModel, covers) = suggestionVm(
            Suggestion("Dune", "Frank Herbert", Suggestion.Source.EMBEDDED, coverBytes = bytes),
        )
        viewModel.suggestForCopy({ File.createTempFile("suggest", ".epub") }, "dune.epub", "EPUB")
        advanceUntilIdle()
        val path = viewModel.metadataLookupState.value.embeddedCoverPath!!
        val saved = File(path)
        assertEquals(covers, saved.parentFile)
        assertTrue(saved.name.startsWith("pending_") && saved.name.endsWith(".jpg"))
        assertTrue(bytes.contentEquals(saved.readBytes()))

        // A manual search afterwards keeps the file's cover as the default.
        viewModel.lookupFn = { _, _ -> MetadataLookupResult.NoMatches }
        viewModel.searchBookMetadata("Dune", "Frank Herbert")
        advanceUntilIdle()
        assertEquals(path, viewModel.metadataLookupState.value.embeddedCoverPath)
        saved.delete()
    }

    @Test
    fun suggestForBook_readsStoredFileDirectly() = runTest(dispatcher) {
        val (viewModel, _) = suggestionVm(Suggestion("Dune", "Frank Herbert", Suggestion.Source.EMBEDDED))
        var copied = false
        viewModel.copyToCacheFn = { _, _ -> copied = true; null }
        var asked: File? = null
        viewModel.suggestFn = { f, _, _ -> asked = f; Suggestion("Dune", "Frank Herbert", Suggestion.Source.EMBEDDED) }
        val b = book(9).copy(filePath = "/data/books/dune.epub", fileName = "dune.epub", format = "EPUB")
        viewModel.suggestForBook(b)
        advanceUntilIdle()
        assertFalse(copied)
        assertEquals("/data/books/dune.epub", asked!!.path)
        assertEquals("Dune", viewModel.metadataLookupState.value.suggestion!!.title)
    }

    // ── Form pre-fill rules (no Compose needed) ──

    @Test
    fun provenanceLabels() {
        assertEquals("From the file", provenanceLabel(Suggestion.Source.EMBEDDED))
        assertEquals("From the first page — check it", provenanceLabel(Suggestion.Source.FIRST_PAGE))
        assertEquals("From the file name — check it", provenanceLabel(Suggestion.Source.FILENAME))
        assertEquals("Matched online", provenanceLabel(Suggestion.Source.ONLINE))
    }

    @Test
    fun form_suggestionPrefillsWhenUntouched_notWhenTyped() {
        val s = Suggestion("The Great Gatsby", "F. Scott Fitzgerald", Suggestion.Source.EMBEDDED)
        val untouched = MetadataFormState("greatgatsby_ebook_free", "")
        untouched.applySuggestion(s, hasEmbeddedCover = false)
        assertEquals("The Great Gatsby", untouched.title)
        assertEquals("F. Scott Fitzgerald", untouched.author)
        assertEquals(Suggestion.Source.EMBEDDED, untouched.provenance)

        val typed = MetadataFormState("greatgatsby_ebook_free", "")
        typed.editTitle("My title")
        typed.applySuggestion(s, hasEmbeddedCover = false)
        assertEquals("My title", typed.title)
        assertEquals("", typed.author)
        assertNull(typed.provenance)
    }

    @Test
    fun searchBookMetadata_offlineShowsMessage() = runTest(dispatcher) {
        val viewModel = newVm(ContextWrapper(null), FakeBookRepository(), KindleSettings())
        viewModel.lookupFn = { _, _ -> MetadataLookupResult.Unavailable(offline = true, message = "") }
        viewModel.searchBookMetadata("Dune", "")
        advanceUntilIdle()
        assertTrue(viewModel.metadataLookupState.value.error!!.startsWith("You're offline"))
    }

    // ── Saving goes through applyMetadata ──

    @Test
    fun updateBookMetadata_usesApplyMetadata() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        val b = book(4)
        viewModel.updateBookMetadata(b, "New Title", "New Author")
        advanceUntilIdle()
        assertEquals(listOf(Triple("New Title", "New Author", null as String?)), repo.applied)
    }

    @Test
    fun transferState_startsIdle() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val viewModel = newVm(ContextWrapper(null), repo, KindleSettings())
        assertEquals(BookViewModel.TransferState.Idle, viewModel.transferState.first())
    }

    private fun prepared() = app.pagedrop.server.PageDropApiClient.PreparedArtifact(
        file = File("/fake/server.mobi"), title = "T", author = "A", format = "MOBI", coverFile = null,
    )

    private fun epubPending() = PendingAdd(uri = TestUri(), displayName = "Dune.epub", format = "EPUB", initialTitle = "Dune")

    private fun decision(convert: Boolean, send: Boolean = false) =
        AddDecision(title = null, author = null, coverUrl = null, convert = convert, serverFormat = "MOBI", sendToKindle = send)

    @Test
    fun keepEpub_addsAsIsWithoutConvertingOrAskingToUpload() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val vm = newVm(ContextWrapper(null), repo, KindleSettings())
        val calls = mutableListOf<String>()
        vm.confirmUploadFn = { calls += "confirm"; true }
        vm.serverConvertFn = { _, _, _, _, _, _ -> calls += "upload"; prepared() }
        vm.confirmAdd(ContextWrapper(null), epubPending(), decision(convert = false), coverUri = null)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), calls)
        assertEquals(0, repo.localConversions)
        assertEquals(1, repo.getBooks().first().size)
        assertFalse(vm.isConverting.value)
    }

    @Test
    fun convertEpub_withoutServerConvertsOnThePhoneAndNeverAsksToUpload() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val vm = newVm(ContextWrapper(null), repo, KindleSettings())
        val calls = mutableListOf<String>()
        vm.confirmUploadFn = { calls += "confirm"; true }
        vm.confirmAdd(ContextWrapper(null), epubPending(), decision(convert = true), coverUri = null)
        advanceUntilIdle()
        assertEquals(emptyList<String>(), calls)
        assertEquals(1, repo.localConversions)
    }

    @Test
    fun addWithServer_declinedConsentNeverUploadsAndConvertsMobiOnThePhone() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val vm = newVm(ContextWrapper(null), repo, KindleSettings())
        val calls = mutableListOf<String>()
        vm.confirmUploadFn = { calls += "confirm"; false }
        vm.serverConvertFn = { _, _, _, _, _, _ -> calls += "upload"; prepared() }
        vm.sourceFormatFn = { _, _ -> "EPUB" }
        vm.addBookWithServerConversion(ContextWrapper(null), TestUri(), targetFormat = "mobi")
        advanceUntilIdle()
        assertEquals(listOf("confirm"), calls)
        assertEquals(1, repo.localConversions)
        assertFalse(vm.isConverting.value)
    }

    @Test
    fun addWithServer_declinedConsentDoesNotUploadServerOnlyFormats() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val vm = newVm(ContextWrapper(null), repo, KindleSettings())
        var uploaded = false
        vm.confirmUploadFn = { false }
        vm.serverConvertFn = { _, _, _, _, _, _ -> uploaded = true; prepared() }
        vm.sourceFormatFn = { _, _ -> "EPUB" }
        val message = async { vm.events.first() }
        vm.addBookWithServerConversion(ContextWrapper(null), TestUri(), targetFormat = "pdf")
        advanceUntilIdle()
        assertFalse(uploaded)
        assertEquals(0, repo.localConversions)
        val event = message.await()
        assertTrue(event is BookViewModel.LibraryEvent.Message && "choose MOBI" in event.text)
    }

    @Test
    fun addWithServer_uploadsOnlyAfterConsentIsGiven() = runTest(dispatcher) {
        val repo = FakeBookRepository()
        val vm = newVm(ContextWrapper(null), repo, KindleSettings())
        val calls = mutableListOf<String>()
        vm.confirmUploadFn = { calls += "confirm"; true }
        vm.serverConvertFn = { _, _, _, _, _, _ -> calls += "upload"; prepared() }
        vm.addBookWithServerConversion(ContextWrapper(null), TestUri(), targetFormat = "mobi")
        advanceUntilIdle()
        assertEquals(listOf("confirm", "upload"), calls)
        assertEquals(0, repo.localConversions)
    }
}

private class FakeBookRepository : BookRepository {
    private val data = mutableListOf<Book>()
    val applied = mutableListOf<Triple<String, String, String?>>()

    fun addBookToTest(book: Book) { data.add(book) }

    override fun getBooks(): Flow<List<Book>> = flowOf(data.toList())

    override suspend fun addBook(context: Context, uri: Uri): Book {
        val book = Book(
            uid = (data.size + 1),
            title = "Test Book",
            author = "Test Author",
            fileName = "test.azw3",
            filePath = "/fake/test.azw3",
            format = "AZW3",
            fileSize = 1000L
        )
        data.add(book)
        return book
    }

    override suspend fun addBookFromFile(book: Book): Long {
        data.add(book)
        return book.uid.toLong()
    }

    override suspend fun convertAndAddBook(context: Context, uri: Uri, outputTitle: String?): Book = addBook(context, uri)

    var localConversions = 0
    override suspend fun convertAndAddBookWithMetadata(
        context: Context, uri: Uri, title: String?, author: String?, coverPath: String?,
    ): Book {
        localConversions++
        return addBook(context, uri)
    }

    override suspend fun deleteBook(book: Book) { data.removeAll { it.uid == book.uid } }

    override suspend fun getBookById(id: Int): Book? = data.find { it.uid == id }

    override suspend fun markTransferred(uid: Int) {}

    override suspend fun markTransferredBookIds(uids: List<Int>) {}

    override suspend fun updateBookMetadata(book: Book, title: String, author: String, coverPath: String?): Book =
        book.copy(title = title, author = author, coverPath = coverPath)

    override suspend fun applyMetadata(book: Book, title: String, author: String, coverPath: String?): Book {
        applied += Triple(title, author, coverPath)
        return book.copy(title = title, author = author, coverPath = coverPath)
    }
}
