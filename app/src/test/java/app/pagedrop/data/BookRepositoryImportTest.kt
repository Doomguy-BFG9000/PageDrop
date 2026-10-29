package app.pagedrop.data

import android.content.ContextWrapper
import app.pagedrop.data.local.database.Book
import app.pagedrop.data.local.database.BookDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BookRepositoryImportTest {
    /** Proxy so this test doesn't break whenever BookDao gains a method. Only insertBook is meaningful. */
    private class RecordingDao {
        val inserted = mutableListOf<Book>()
        val dao: BookDao = java.lang.reflect.Proxy.newProxyInstance(
            BookDao::class.java.classLoader, arrayOf(BookDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "insertBook" -> { inserted += args[0] as Book; inserted.size.toLong() }
                "getBooks" -> flowOf(inserted)
                "getBookById" -> inserted.firstOrNull { it.uid == args[0] as Int }
                else -> Unit
            }
        } as BookDao
    }

    private fun context(dir: File) = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    @Test
    fun importMovesFileIntoBooksDirAndInsertsRow() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("import").toFile()
        val download = File(root, "dl/Frankenstein - Mary Shelley.epub").apply { parentFile.mkdirs(); writeBytes("PK\u0003\u0004data".toByteArray()) }
        val dao = RecordingDao()
        val repo = DefaultBookRepository(dao.dao, null, context(File(root, "files")))

        val book = repo.importDownloaded(download, "Frankenstein", "Mary Shelley", null)

        assertFalse(download.exists())
        assertEquals("EPUB", book.format)
        assertEquals("Frankenstein", book.title)
        assertEquals("Mary Shelley", book.author)
        assertEquals(1, book.uid)
        assertTrue(File(book.filePath).isFile)
        assertEquals(File(root, "files/books").absolutePath, File(book.filePath).parentFile.absolutePath)
        assertEquals(book.title, dao.inserted.single().title)
    }

    private fun pdfBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        com.tom_roush.pdfbox.pdmodel.PDDocument().use { doc ->
            doc.addPage(com.tom_roush.pdfbox.pdmodel.PDPage())
            doc.save(out)
        }
        return out.toByteArray()
    }

    @Test
    fun importedPdfIsStoredByteForByteWithItsOriginalName() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("import-pdf").toFile()
        val bytes = pdfBytes()
        val download = File(root, "dl/Paper.pdf").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val repo = DefaultBookRepository(RecordingDao().dao, null, context(File(root, "files")))
        val cover = ByteArray(3_000) { 7 }.let { byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + it }

        val book = repo.importDownloaded(download, "Catalog Title", "Catalog Author", null, coverBytes = cover)

        assertEquals("Catalog Title", book.title)
        assertEquals("Paper.pdf", File(book.filePath).name)
        assertTrue(bytes.contentEquals(File(book.filePath).readBytes()))
    }

    @Test
    fun importUsesFallbacksAndAvoidsNameCollisions() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("import2").toFile()
        val repo = DefaultBookRepository(RecordingDao().dao, null, context(File(root, "files")))
        fun download() = File(root, "dl/Same.txt").apply { parentFile.mkdirs(); writeText("hello") }
        val a = repo.importDownloaded(download(), "  ", "", null)
        val b = repo.importDownloaded(download(), "Same", "Someone", null)
        assertEquals("Same", a.title)
        assertEquals("Unknown Author", a.author)
        assertEquals("TXT", a.format)
        assertTrue(a.filePath != b.filePath)
        assertTrue(File(a.filePath).isFile && File(b.filePath).isFile)
    }

    @Test
    fun convertImportedLeavesKindleNativeBooksUntouched() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("import3").toFile()
        val repo = DefaultBookRepository(RecordingDao().dao, null, context(File(root, "files")))
        val mobi = Book(uid = 3, title = "T", author = "A", fileName = "t.mobi", filePath = "/nope/t.mobi", format = "MOBI", fileSize = 1)
        assertEquals(mobi, repo.convertImported(mobi))
    }
}
