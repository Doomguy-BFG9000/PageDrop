package app.pagedrop.data

import app.pagedrop.converter.MobiWriter
import app.pagedrop.data.local.database.Book
import app.pagedrop.data.local.database.BookDao
import app.pagedrop.metadata.MobiMetadataRewriter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookRepositoryApplyMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Dao : BookDao {
        val calls = mutableListOf<String>()
        override fun getBooks(): Flow<List<Book>> = flowOf(emptyList())
        override suspend fun getBookById(uid: Int): Book? = null
        override suspend fun insertBook(item: Book): Long { calls += "insert:${item.uid}:${item.fileName}:${item.title}"; return item.uid.toLong() }
        override suspend fun deleteBook(item: Book) { calls += "delete:${item.uid}" }
        override suspend fun markTransferred(ids: List<Int>, timestamp: Long) {}
        override suspend fun clearTransferred(ids: List<Int>) {}
        override suspend fun updateLastTransferred(uid: Int, timestamp: Long) {}
        override suspend fun updateCoverAndUuid(uid: Int, coverPath: String?, kindleUuid: String) {}
        override suspend fun updateMetadata(uid: Int, title: String, author: String, coverPath: String?) { calls += "update:$uid:$title:$author" }
        override suspend fun updateFileAndMetadata(uid: Int, fileName: String, filePath: String, fileSize: Long, title: String, author: String, coverPath: String?) {
            calls += "updateFile:$uid:$fileName:$title:$author"
        }
    }

    private val jpeg = ByteArray(3000) { if (it < 3) listOf(0xFF, 0xD8, 0xFF)[it].toByte() else (it % 251).toByte() }

    private fun mobiBook(name: String = "flowersforalgernon.mobi"): Book {
        val f = tmp.newFile(name)
        MobiWriter("flowersforalgernon", "Unknown Author", "<html><body><p>x</p></body></html>".toByteArray(), coverImage = jpeg).write(f)
        return Book(uid = 7, title = "flowersforalgernon", author = "Unknown Author", fileName = f.name, filePath = f.absolutePath, format = "MOBI", fileSize = f.length(), addedDate = 123L)
    }

    @Test fun embedsRenamesAndUpdatesRow() = runTest {
        val dao = Dao()
        val book = mobiBook()
        val cover = tmp.newFile("c.jpg").apply { writeBytes(jpeg.copyOf(4000).also { it[3500] = 9 }) }
        val updated = DefaultBookRepository(dao).applyMetadata(book, " Flowers for Algernon ", "Daniel Keyes", cover.absolutePath)
        assertEquals("Flowers for Algernon - Daniel Keyes.mobi", updated.fileName)
        assertEquals(7, updated.uid); assertEquals(123L, updated.addedDate)
        assertFalse(File(book.filePath).exists())
        val info = MobiMetadataRewriter.read(File(updated.filePath))
        assertEquals("Flowers for Algernon", info.title); assertEquals("Daniel Keyes", info.author)
        assertEquals(File(updated.filePath).length(), updated.fileSize)
        assertEquals(listOf("updateFile:7:Flowers for Algernon - Daniel Keyes.mobi:Flowers for Algernon:Daniel Keyes"), dao.calls)
    }

    @Test fun unchangedNameUsesPlainUpdate() = runTest {
        val dao = Dao()
        val book = mobiBook("Flowers for Algernon - Daniel Keyes.mobi")
        val updated = DefaultBookRepository(dao).applyMetadata(book, "Flowers for Algernon", "Daniel Keyes", null)
        assertEquals(book.filePath, updated.filePath)
        assertEquals(listOf("updateFile:7:Flowers for Algernon - Daniel Keyes.mobi:Flowers for Algernon:Daniel Keyes"), dao.calls)
    }

    @Test fun failedEmbedStillUpdatesDbAndKeepsFile() = runTest {
        val dao = Dao()
        val f = tmp.newFile("broken.mobi").apply { writeBytes(ByteArray(300) { 1 }) }
        val book = Book(uid = 1, title = "broken", author = "Unknown Author", fileName = f.name, filePath = f.absolutePath, format = "MOBI", fileSize = 300)
        val updated = DefaultBookRepository(dao).applyMetadata(book, "Real Title", "Real Author", null)
        assertTrue(f.exists())
        assertEquals("Real Title", updated.title)
        assertEquals(listOf("updateFile:1:broken.mobi:Real Title:Real Author"), dao.calls)
    }

    @Test fun epubIsDbOnly() = runTest {
        val dao = Dao()
        val f = tmp.newFile("x.epub").apply { writeText("epub") }
        val book = Book(uid = 2, title = "x", author = "Unknown Author", fileName = f.name, filePath = f.absolutePath, format = "EPUB", fileSize = 4)
        val updated = DefaultBookRepository(dao).applyMetadata(book, "X", "Y", null)
        assertEquals("x.epub", updated.fileName)
        assertEquals(listOf("updateFile:2:x.epub:X:Y"), dao.calls)
    }
}
