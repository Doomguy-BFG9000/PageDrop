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

package app.pagedrop.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import app.pagedrop.data.local.database.Book
import app.pagedrop.data.local.database.BookDao

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBookRepositoryTest {

    @Test
    fun detectFormat_knownExtensions_returnsCorrectFormat() = runTest {
        assertEquals("AZW3", DefaultBookRepository.detectFormat("book.azw3"))
        assertEquals("MOBI", DefaultBookRepository.detectFormat("book.mobi"))
        assertEquals("PDF", DefaultBookRepository.detectFormat("book.pdf"))
        assertEquals("EPUB", DefaultBookRepository.detectFormat("book.epub"))
        assertEquals("TXT", DefaultBookRepository.detectFormat("book.txt"))
    }

    @Test
    fun detectFormat_unknownExtension_returnsUppercase() = runTest {
        assertEquals("DOCX", DefaultBookRepository.detectFormat("book.docx"))
    }

    @Test
    fun formatFileSize_formatsCorrectly() = runTest {
        assertEquals("512 B", DefaultBookRepository.formatFileSize(512))
        assertEquals("1.0 KB", DefaultBookRepository.formatFileSize(1024))
        assertEquals("1.5 MB", DefaultBookRepository.formatFileSize(1572864))
        assertEquals("1.07 GB", DefaultBookRepository.formatFileSize(1153433600))
    }

    @Test
    fun getBooks_returnsFlowFromDao() = runTest {
        val dao = FakeBookDao()
        val repo = DefaultBookRepository(dao)
        dao.setBooks(listOf(
            Book(uid = 1, title = "Test", author = "Author", fileName = "test.mobi", filePath = "/fake/test.mobi", format = "MOBI", fileSize = 1000)
        ))
        val books = repo.getBooks().first()
        assertEquals(1, books.size)
        assertEquals("Test", books[0].title)
    }

    @Test
    fun markTransferred_updatesTimestamp() = runTest {
        val dao = FakeBookDao()
        val repo = DefaultBookRepository(dao)
        val book = Book(uid = 1, title = "T", author = "A", fileName = "f", filePath = "/p", format = "MOBI", fileSize = 100)
        dao.setBooks(listOf(book))
        repo.markTransferred(1)
        val updated = dao.lastTransferredUid
        assertEquals(1, updated)
    }

    @Test
    fun markTransferredBookIds_updatesAll() = runTest {
        val dao = FakeBookDao()
        val repo = DefaultBookRepository(dao)
        repo.markTransferredBookIds(listOf(1, 2, 3))
        assertEquals(listOf(1, 2, 3), dao.transferredUids)
    }

    @Test
    fun addBookFromFile_insertsAndReturnsId() = runTest {
        val dao = FakeBookDao()
        val repo = DefaultBookRepository(dao)
        val book = Book(title = "T", author = "A", fileName = "f", filePath = "/p", format = "MOBI", fileSize = 100)
        val id = repo.addBookFromFile(book)
        assertEquals(0, id) // FakeDao returns 0
        assertNotNull(dao.insertedBook)
        assertEquals("T", dao.insertedBook?.title)
    }
}

private class FakeBookDao : BookDao {
    private var books = listOf<Book>()
    var lastTransferredUid: Int? = null
    val transferredUids = mutableListOf<Int>()
    var insertedBook: Book? = null

    fun setBooks(list: List<Book>) { books = list }

    override fun getBooks(): Flow<List<Book>> = flow { emit(books) }

    override suspend fun getBookById(uid: Int): Book? = books.find { it.uid == uid }

    override suspend fun insertBook(item: Book): Long { insertedBook = item; return 0 }

    override suspend fun deleteBook(item: Book) {}

    override suspend fun updateLastTransferred(uid: Int, timestamp: Long) {
        lastTransferredUid = uid
        transferredUids.add(uid)
    }

    override suspend fun updateMetadata(uid: Int, title: String, author: String, coverPath: String?) {}

    override suspend fun updateCoverAndUuid(uid: Int, coverPath: String?, kindleUuid: String) {}
}
