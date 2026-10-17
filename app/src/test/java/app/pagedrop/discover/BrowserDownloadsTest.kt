package app.pagedrop.discover

import org.junit.Assert.*
import org.junit.Test
import okhttp3.HttpUrl.Companion.toHttpUrl

class BrowserDownloadsTest {
    private val session = BrowserDownloadSession("Browser UA", "https://annas-archive.gl/slow_download/abc/0/8", { null })

    @Test fun annaFilenameKeepsTitleAuthorAndFormatWithoutArchiveSuffix() {
        val r = BrowserDownloads.capture(
            "https://files.example/download", "attachment; filename=\"गोदान -- प्रेमचंद -- 1936 -- abcd -- Anna’s Archive.epub\"",
            "application/octet-stream", "Anna’s Archive", session, 1234,
        )!!
        assertEquals("गोदान", r.title)
        assertEquals("प्रेमचंद", r.author)
        assertEquals(SourceKind.WEBSITE, r.source)
        assertEquals(BookFormat.EPUB, r.options.single().format)
        assertEquals(1234L, r.options.single().sizeBytes)
        assertSame(session, r.options.single().browserSession)
        assertFalse(SourceKind.WEBSITE.isBuiltIn)
    }

    @Test fun unicodeDispositionTakesPrecedenceAndPreservesLiteralPlus() {
        val r = BrowserDownloads.capture("https://files.example/a.bin",
            "attachment; filename=wrong.pdf; filename*=UTF-8''%E0%A4%97%E0%A5%8B%E0%A4%A6%E0%A4%BE%E0%A4%A8%20%2B%20Notes.epub",
            null, "Library", session)!!
        assertEquals("गोदान + Notes", r.title)
        assertEquals(BookFormat.EPUB, r.options.single().format)
    }

    @Test fun signedPathAndMimeDownloadWithoutExtensionAreSupported() {
        val r = BrowserDownloads.capture("https://files.example/fetch?token=abc", null,
            "application/epub+zip", "Library", session)!!
        assertEquals("https://files.example/fetch?token=abc", r.options.single().url)
        assertTrue(BrowserDownloads.isBookLink("https://files.example/a.pdf?token=signed"))
        assertFalse(BrowserDownloads.isBookLink("https://site.example/slow_download/book/0/8"))
    }

    @Test fun unsupportedUnsafeAndHtmlDownloadsAreRejected() {
        for (url in listOf("file:///sdcard/a.epub", "content://a.epub", "javascript:alert(1)", "http://files.example/a.epub", "https://u:p@files.example/a.epub", "https://files.example/app.apk")) {
            assertNull(url, BrowserDownloads.capture(url, null, null, "Library", session))
        }
        assertNull(BrowserDownloads.capture("https://files.example/a.epub", null, "text/html; charset=utf-8", "Library", session))
    }

    @Test fun refererNeverContainsUrlCredentialsOrCrossOriginQuery() {
        val s = BrowserDownloadSession("UA", "https://user:secret@site.example/book?token=private#section", { null })
        assertEquals("https://site.example/book?token=private", s.refererFor("https://site.example/file".toHttpUrl()))
        assertEquals("https://site.example/", s.refererFor("https://files.example/file".toHttpUrl()))
        assertNull(s.refererFor("http://files.example/file".toHttpUrl()))
    }
}
