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
import android.net.Uri
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.pagedrop.data.BookRepository
import app.pagedrop.data.KindleSettings
import app.pagedrop.data.local.database.Book

@OptIn(ExperimentalCoroutinesApi::class)
class BookViewModelTest {

    @Test
    fun uiState_initiallyLoading() = runTest {
        val viewModel = BookViewModel(FakeBookRepository(), KindleSettings())
        assertEquals(LibraryUiState.Loading, viewModel.uiState.first())
    }

    @Test
    fun transferQueue_toggleQueued_addsBook() = runTest {
        val repo = FakeBookRepository()
        val viewModel = BookViewModel(repo, KindleSettings())
        val book = Book(uid = 1, title = "T", author = "A", fileName = "f.mobi", filePath = "/p/f.mobi", format = "MOBI", fileSize = 100)
        repo.addBookToTest(book)

        viewModel.toggleQueued(book)
        val queue = viewModel.transferQueue.first()
        assertEquals(1, queue.size)
        assertEquals(1, queue[0].uid)
    }

    @Test
    fun transferQueue_toggleQueued_removesBook() = runTest {
        val repo = FakeBookRepository()
        val viewModel = BookViewModel(repo, KindleSettings())
        val book = Book(uid = 2, title = "T2", author = "A2", fileName = "f2.mobi", filePath = "/p/f2.mobi", format = "MOBI", fileSize = 200)
        repo.addBookToTest(book)
        viewModel.toggleQueued(book)
        viewModel.toggleQueued(book)
        val queue = viewModel.transferQueue.first()
        assertEquals(0, queue.size)
    }

    @Test
    fun isQueued_returnsTrueForQueuedBook() = runTest {
        val repo = FakeBookRepository()
        val viewModel = BookViewModel(repo, KindleSettings())
        val book = Book(uid = 3, title = "T3", author = "A3", fileName = "f3.mobi", filePath = "/p/f3.mobi", format = "MOBI", fileSize = 300)
        repo.addBookToTest(book)
        viewModel.toggleQueued(book)
        assertTrue(viewModel.isQueued(book))
    }

    @Test
    fun transferState_startsIdle() = runTest {
        val repo = FakeBookRepository()
        val viewModel = BookViewModel(repo, KindleSettings())
        assertEquals(BookViewModel.TransferState.Idle, viewModel.transferState.first())
    }
}

private class FakeBookRepository : BookRepository {
    private val data = mutableListOf<Book>()

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

    override suspend fun deleteBook(book: Book) { data.removeAll { it.uid == book.uid } }

    override suspend fun getBookById(id: Int): Book? = data.find { it.uid == id }

    override suspend fun markTransferred(uid: Int) {}

    override suspend fun markTransferredBookIds(uids: List<Int>) {}
}
