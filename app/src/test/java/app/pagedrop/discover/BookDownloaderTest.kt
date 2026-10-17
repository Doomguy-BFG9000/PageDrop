package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BookDownloaderTest {
    private fun book(source: SourceKind = SourceKind.GUTENBERG, id: String = "gutenberg:84") =
        BookResult(id, source, "Src", "Frankenstein", "Mary Shelley", options = emptyList())

    private fun mobi(size: Int = 200) = ByteArray(size).also { "BOOKMOBI".toByteArray().copyInto(it, 60) }
    private fun epub(): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            for ((name, text) in mapOf("mimetype" to "application/epub+zip", "META-INF/container.xml" to "<container/>")) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
    }.toByteArray()
    private fun downloader(http: FakeHttp, dir: File = tempDir(), creds: (String, String) -> BasicAuth? = { _, _ -> null }) =
        BookDownloader(dir, http, creds)

    private fun run(d: BookDownloader, opt: DownloadOption, r: BookResult = book(), progress: MutableList<Float?> = mutableListOf()): File =
        runBlocking { d.download(r, opt) { progress += it } }

    @Test
    fun downloadsValidMobiNamedTitleDashAuthor() {
        val http = FakeHttp().apply { streamBytes = mapOf("84.kindle.images" to mobi(5000)) }
        val progress = mutableListOf<Float?>()
        val file = run(downloader(http), DownloadOption(BookFormat.MOBI, "https://x/84.kindle.images"), progress = progress)
        assertEquals("Frankenstein - Mary Shelley.mobi", file.name)
        assertEquals(5000L, file.length())
        assertEquals(0f, progress.first()); assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> (a ?: 0f) <= (b ?: 0f) })
    }

    @Test
    fun azw3ValidatesAsMobiMagic() {
        val http = FakeHttp().apply { streamBytes = mapOf("a.azw3" to mobi()) }
        assertEquals("azw3", run(downloader(http), DownloadOption(BookFormat.AZW3, "https://x/a.azw3")).extension)
    }

    @Test
    fun acceptsEpubPdfAndText() {
        val http = FakeHttp().apply {
            streamBytes = mapOf("e.epub" to epub(), "p.pdf" to "%PDF-1.7\n...".toByteArray(), "t.txt" to "Hello world".toByteArray())
        }
        val d = downloader(http)
        assertEquals("epub", run(d, DownloadOption(BookFormat.EPUB, "https://x/e.epub")).extension)
        assertEquals("pdf", run(d, DownloadOption(BookFormat.PDF, "https://x/p.pdf")).extension)
        assertEquals("txt", run(d, DownloadOption(BookFormat.TXT, "https://x/t.txt")).extension)
    }

    @Test
    fun ordinaryZipAndTruncatedZipAreNotEpubs() {
        val bytes = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("readme.txt")); zip.write("not a book".toByteArray()); zip.closeEntry() }
        }.toByteArray()
        val http = FakeHttp().apply { streamBytes = mapOf("ordinary.epub" to bytes, "cut.epub" to epub().copyOf(40)) }
        val dir = tempDir()
        val d = downloader(http, dir)
        for (name in listOf("ordinary", "cut")) {
            assertTrue(runCatching { run(d, DownloadOption(BookFormat.EPUB, "https://x/$name.epub")) }.exceptionOrNull() is DownloadException)
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test
    fun htmlInsteadOfBookIsRejectedAndTempFileRemoved() {
        val dir = tempDir()
        val http = FakeHttp().apply { streamBytes = mapOf("a.azw3" to "<!DOCTYPE html><html>Your Download Has Started</html>".toByteArray()) }
        val e = runCatching { run(downloader(http, dir), DownloadOption(BookFormat.AZW3, "https://x/a.azw3")) }.exceptionOrNull()
        assertTrue(e is DownloadException && e.message!!.contains("web page"))
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test
    fun wrongMagicIsRejected() {
        val http = FakeHttp().apply { streamBytes = mapOf("a.pdf" to "PK\u0003\u0004".toByteArray(), "b.epub" to "%PDF-1.4".toByteArray()) }
        val d = downloader(http)
        assertTrue(runCatching { run(d, DownloadOption(BookFormat.PDF, "https://x/a.pdf")) }.exceptionOrNull() is DownloadException)
        assertTrue(runCatching { run(d, DownloadOption(BookFormat.EPUB, "https://x/b.epub")) }.exceptionOrNull() is DownloadException)
    }

    @Test
    fun binaryIsNotText() {
        val http = FakeHttp().apply { streamBytes = mapOf("t.txt" to byteArrayOf(1, 2, 0, 4)) }
        assertTrue(runCatching { run(downloader(http), DownloadOption(BookFormat.TXT, "https://x/t.txt")) }.exceptionOrNull() is DownloadException)
    }

    @Test
    fun httpErrorsAndEmptyBodiesGivePlainMessages() {
        val http = FakeHttp().apply { streamCode = 404 }
        val e404 = runCatching { run(downloader(http), DownloadOption(BookFormat.PDF, "https://x/a.pdf")) }.exceptionOrNull()!!
        assertTrue(e404.message!!.contains("no longer available"))
        val empty = FakeHttp()
        assertTrue(runCatching { run(downloader(empty), DownloadOption(BookFormat.PDF, "https://x/a.pdf")) }.exceptionOrNull()!!.message!!.contains("empty"))
    }

    @Test
    fun oversizeIsRefusedBeforeDownloading() {
        val http = FakeHttp().apply { streamBytes = mapOf("a.pdf" to "%PDF-".toByteArray()); streamContentLength = BookDownloader.MAX_BYTES + 1 }
        val e = runCatching { run(downloader(http), DownloadOption(BookFormat.PDF, "https://x/a.pdf")) }.exceptionOrNull()!!
        assertTrue(e.message!!.contains("300 MB"))
    }

    @Test
    fun truncatedDownloadIsRejected() {
        val http = FakeHttp().apply { streamBytes = mapOf("a.pdf" to "%PDF-1.4 short".toByteArray()); streamContentLength = 99999 }
        assertTrue(runCatching { run(downloader(http), DownloadOption(BookFormat.PDF, "https://x/a.pdf")) }.exceptionOrNull()!!.message!!.contains("cut short"))
    }

    @Test
    fun unknownSizeReportsNullProgress() {
        val http = FakeHttp().apply { streamBytes = mapOf("a.pdf" to "%PDF-1.4 ok".toByteArray()); streamContentLength = -1 }
        val progress = mutableListOf<Float?>()
        run(downloader(http), DownloadOption(BookFormat.PDF, "https://x/a.pdf"), progress = progress)
        assertNull(progress.first())
    }

    @Test
    fun opdsDownloadsCarryCatalogCredentialsOnly() {
        val http = FakeHttp().apply { streamBytes = mapOf("a.pdf" to "%PDF-1.4 ok".toByteArray()) }
        val d = downloader(http) { id, url -> if (id == "cat1" && url.contains("lib.example.org")) BasicAuth("u", "p") else null }
        val r = book(SourceKind.OPDS, "opds:cat1:urn:x:1")
        run(d, DownloadOption(BookFormat.PDF, "https://lib.example.org/a.pdf"), r)
        run(d, DownloadOption(BookFormat.PDF, "https://other.example.net/a.pdf"), r)
        assertEquals(BasicAuth("u", "p"), http.requests[0].second)
        assertNull(http.requests[1].second)
        // Built-in sources never get credentials.
        run(d, DownloadOption(BookFormat.PDF, "https://lib.example.org/a.pdf"), book())
        assertNull(http.requests[2].second)
    }
}
