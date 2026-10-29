package app.pagedrop.data

import android.content.ContextWrapper
import app.pagedrop.data.local.database.Book
import app.pagedrop.data.local.database.BookDao
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Covers for books saved from the Kindle: passed-in bytes on import, and the backfill from book files. */
class BookRepositoryCoverTest {
    /** Proxy so this test doesn't break whenever BookDao gains a method. */
    private class FakeDao(books: List<Book> = emptyList()) {
        val inserted = mutableListOf<Book>()
        val coverUpdates = mutableMapOf<Int, String?>()
        private val existing = books
        val dao: BookDao = java.lang.reflect.Proxy.newProxyInstance(
            BookDao::class.java.classLoader, arrayOf(BookDao::class.java),
        ) { _, method, args ->
            when (method.name) {
                "insertBook" -> { inserted += args[0] as Book; inserted.size.toLong() }
                "getBooks" -> flowOf(existing + inserted)
                "getBookById" -> (existing + inserted).firstOrNull { it.uid == args[0] as Int }
                "updateMetadata" -> { coverUpdates[args[0] as Int] = args[3] as String?; Unit }
                else -> Unit
            }
        } as BookDao
    }

    private fun context(dir: File) = object : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    /** Smallest bytes the app treats as a real JPEG cover. */
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(3_000) { 7 }

    private fun epubWithCover(file: File, cover: ByteArray?): File {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, bytes: ByteArray) { zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() }
            put("mimetype", "application/epub+zip".toByteArray())
            put(
                "META-INF/container.xml",
                """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray(),
            )
            val coverItem = if (cover != null) """<item id="c" href="images/cover.jpg" properties="cover-image"/>""" else ""
            put(
                "OEBPS/content.opf",
                """<package><metadata><dc:title>The Hunger Games</dc:title><dc:creator>Suzanne Collins</dc:creator></metadata>
                   <manifest>$coverItem</manifest></package>""".toByteArray(),
            )
            if (cover != null) put("OEBPS/images/cover.jpg", cover)
        }
        return file
    }

    @Test
    fun importSavesPassedInCoverBytes() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("cover-import").toFile()
        val download = File(root, "dl/Hunger.epub").apply { parentFile.mkdirs(); writeBytes("PK\u0003\u0004data".toByteArray()) }
        val repo = DefaultBookRepository(FakeDao().dao, null, context(File(root, "files")))

        val book = repo.importDownloaded(download, "The Hunger Games", "Suzanne Collins", coverUrl = null, coverBytes = jpeg)

        val cover = File(book.coverPath!!)
        assertTrue(cover.isFile)
        assertArrayEquals(jpeg, cover.readBytes())
    }

    @Test
    fun importIgnoresBytesThatAreNotAnImage() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("cover-junk").toFile()
        val download = File(root, "dl/Hunger.epub").apply { parentFile.mkdirs(); writeBytes("PK\u0003\u0004data".toByteArray()) }
        val repo = DefaultBookRepository(FakeDao().dao, null, context(File(root, "files")))

        val book = repo.importDownloaded(download, "T", "A", coverUrl = null, coverBytes = ByteArray(5_000))

        assertNull(book.coverPath)
    }

    @Test
    fun backfillTakesCoverFromBookFileAndSkipsOthers() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("cover-backfill").toFile()
        val files = File(root, "files")
        val withCover = epubWithCover(File(files, "books/The Hunger Games.epub"), jpeg)
        val noCover = epubWithCover(File(files, "books/Plain.epub"), null)
        val existingCover = File(files, "covers/Kept_cover.jpg").apply { parentFile.mkdirs(); writeBytes(jpeg) }
        val pdf = File(files, "books/Paper.pdf").apply { writeBytes("%PDF-1.4".toByteArray()) }
        fun book(uid: Int, f: File, format: String, cover: String? = null) =
            Book(uid = uid, title = f.nameWithoutExtension, author = "A", fileName = f.name, filePath = f.path, format = format, fileSize = f.length(), coverPath = cover)
        val dao = FakeDao(
            listOf(
                book(1, withCover, "EPUB"),
                book(2, noCover, "EPUB"),
                book(3, withCover, "EPUB", cover = existingCover.path),
                book(4, pdf, "PDF"),
                book(5, File(files, "books/Missing.mobi"), "MOBI"),
            )
        )
        val repo = DefaultBookRepository(dao.dao, null, context(files))

        val filled = repo.fillMissingCoversFromFiles()

        assertEquals(1, filled)
        assertEquals(setOf(1), dao.coverUpdates.keys)
        assertArrayEquals(jpeg, File(dao.coverUpdates[1]!!).readBytes())
    }

    @Test
    fun backfillWithoutContextDoesNothing() = runBlocking {
        assertEquals(0, DefaultBookRepository(FakeDao().dao).fillMissingCoversFromFiles())
    }


    @Test
    fun onlineSearchSavesConfidentCoverAndReportsMisses() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("cover-online").toFile()
        val files = File(root, "files")
        fun book(uid: Int, title: String, author: String, format: String = "EPUB", cover: String? = null) =
            File(files, "books/$title.${format.lowercase()}").let { f ->
                f.parentFile.mkdirs(); f.writeBytes("PK\u0003\u0004".toByteArray())
                Book(uid = uid, title = title, author = author, fileName = f.name, filePath = f.path, format = format, fileSize = f.length(), coverPath = cover)
            }
        val kept = File(files, "covers/Kept_cover.jpg").apply { parentFile.mkdirs(); writeBytes(jpeg) }
        val dao = FakeDao(
            listOf(
                book(1, "The Hunger Games", "Suzanne Collins"),
                book(2, "Obscure Pamphlet", "Unknown Author"),
                book(3, "Already Covered", "A", cover = kept.path),
                book(4, "Skipped Before", "B"),
            )
        )
        val queries = mutableListOf<Pair<String, String>>()
        val repo = DefaultBookRepository(dao.dao, null, context(files)).apply {
            coverUrlLookup = { title, author -> queries += title to author; if (title == "The Hunger Games") "https://covers.example/hg.jpg" else null }
            coverDownload = { jpeg }
        }

        val results = repo.findMissingCoversOnline(skipUids = setOf(4))

        assertEquals(listOf(1 to true, 2 to false), results)
        assertEquals(listOf("The Hunger Games" to "Suzanne Collins", "Obscure Pamphlet" to ""), queries)
        assertArrayEquals(jpeg, File(dao.coverUpdates[1]!!).readBytes())
        assertEquals(setOf(1), dao.coverUpdates.keys)
    }

    @Test
    fun onlineSearchRejectsDownloadsThatAreNotImages() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("cover-online-junk").toFile()
        val f = File(root, "files/books/T.epub").apply { parentFile.mkdirs(); writeBytes("PK".toByteArray()) }
        val dao = FakeDao(listOf(Book(uid = 1, title = "T", author = "A", fileName = f.name, filePath = f.path, format = "EPUB", fileSize = 2)))
        val repo = DefaultBookRepository(dao.dao, null, context(File(root, "files"))).apply {
            coverUrlLookup = { _, _ -> "https://covers.example/x" }
            coverDownload = { "<html>not found</html>".toByteArray() }
        }
        assertEquals(listOf(1 to false), repo.findMissingCoversOnline(emptySet()))
        assertTrue(dao.coverUpdates.isEmpty())
    }

    @Test
    fun onlineSearchStoresPdfCoverForTheLibraryWithoutRewritingTheFile() = runBlocking {
        val root = java.nio.file.Files.createTempDirectory("cover-online-pdf").toFile()
        val files = File(root, "files")
        val bytes = java.io.ByteArrayOutputStream().also { out ->
            com.tom_roush.pdfbox.pdmodel.PDDocument().use { d -> d.addPage(com.tom_roush.pdfbox.pdmodel.PDPage()); d.save(out) }
        }.toByteArray()
        val f = File(files, "books/Paper.pdf").apply { parentFile.mkdirs(); writeBytes(bytes) }
        val dao = FakeDao(listOf(Book(uid = 1, title = "Paper", author = "A", fileName = f.name, filePath = f.path, format = "PDF", fileSize = f.length())))
        val repo = DefaultBookRepository(dao.dao, null, context(files)).apply {
            coverUrlLookup = { _, _ -> "https://covers.example/p.jpg" }
            coverDownload = { jpeg }
        }

        assertEquals(listOf(1 to true), repo.findMissingCoversOnline(emptySet()))

        assertArrayEquals(jpeg, File(dao.coverUpdates[1]!!).readBytes())
        assertArrayEquals(bytes, f.readBytes())
        assertTrue(File(files, "books/Paper.pdf").isFile)
    }
}
