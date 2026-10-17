package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IndianSourcesTest {
    @Test fun hindwiParsesActualSingleQuotedCardsAndKeepsHindi() {
        val books = RekhtaSource.parse(fixture("hindwi_search_godaan.html"), "https://www.hindwi.org", SourceKind.HINDWI, "Hindwi")
        val godaan = books.first()
        assertEquals("गोदान", godaan.title)
        assertEquals("hi", godaan.language)
        assertEquals("https://www.hindwi.org/ebooks/detail/godaan-ebooks-1", godaan.websiteUrl)
        assertTrue(godaan.options.isEmpty())
        assertFalse(godaan.needsResolve)
        assertEquals("राम वशिष्ठ", books[1].author)
    }

    @Test fun rekhtaParsesSearchWithoutAssumingHindiFromTheInterface() {
        val book = RekhtaSource.parse(fixture("rekhta_search_raag.html"), "https://www.rekhta.org", SourceKind.REKHTA, "Rekhta").single()
        assertEquals("Raag Darbari", book.title)
        assertEquals("Shiri Laal Shukl", book.author)
        assertEquals(1974, book.year)
        assertNull(book.language)
        assertTrue(book.websiteUrl!!.contains("/ebook-detail/raag-darbari"))
        assertTrue(book.coverUrl!!.startsWith("https://"))
    }

    @Test fun emptySearchIsValidButChallengePageIsNotNoResults() {
        assertTrue(RekhtaSource.parse("<div class='searchEbookMatchCards'></div>", "https://www.hindwi.org", SourceKind.HINDWI, "Hindwi").isEmpty())
        assertTrue(runCatching { RekhtaSource.parse("<html>Access denied</html>", "https://www.hindwi.org", SourceKind.HINDWI, "Hindwi") }.exceptionOrNull() is SourceUnavailableException)
    }

    @Test fun wikisourceOmitsChapterHitsAndKeepsReaderLinkWithoutGuessingACompleteEpub() {
        val books = WikisourceSource.parse(fixture("wikisource_search_godaan.json"))
        assertTrue(books.any { it.title == "गो-दान" })
        assertTrue(books.none { '/' in it.title })
        assertTrue(books.all { it.options.isEmpty() && it.websiteUrl!!.startsWith("https://hi.wikisource.org/wiki/") })
    }

    @Test fun openLibraryBorrowingIsNotAdvertisedAsADownload() {
        val books = OpenLibrarySource.parse(fixture("openlibrary_search.json"))
        assertEquals(2, books.size)
        val borrowed = books.first { it.id.endsWith("OL332076W") }
        assertTrue(borrowed.options.isEmpty())
        assertFalse(borrowed.needsResolve)
        assertNull(borrowed.archiveIdentifier)
        assertNotNull(borrowed.websiteUrl)
        val open = books.first { it.needsResolve }
        assertEquals("godaan-open", open.archiveIdentifier)
        assertTrue(open.options.isEmpty())
    }

    @Test fun openLibraryChecksTheExactArchiveEditionBeforeOfferingDownloads() = runBlocking {
        val http = FakeHttp().apply { on("archive.org/metadata/godaan-open", fixture("ia_metadata_open.json")) }
        val book = OpenLibrarySource.parse(fixture("openlibrary_search.json")).first { it.needsResolve }
        val resolved = BookSearchEngine(newSettings(), http, 5_000).resolve(book)
        assertFalse(resolved.needsResolve)
        assertTrue(resolved.options.isNotEmpty())
        assertTrue(resolved.options.all { "/download/godaan-open/" in it.url })
        assertEquals(book.websiteUrl, resolved.websiteUrl)
    }

    @Test fun restrictedCollectionsAndEncryptedFilesDoNotBecomeDownloadOptions() {
        val restricted = """{"metadata":{"collection":["printdisabled"]},"files":[{"name":"a.epub","format":"EPUB"}]}"""
        assertTrue(InternetArchiveSource.parseMetadata("x", restricted).isEmpty())
        val encrypted = """{"metadata":{},"files":[{"name":"a.azw3","format":"Encrypted Kindle"},{"name":"a.mobi","format":"Encrypted MOBI"}]}"""
        assertTrue(InternetArchiveSource.parseMetadata("x", encrypted).isEmpty())
    }

    @Test fun languageAliasesAndWebsiteQueriesAreNormalized() {
        assertEquals("hi", TextUtil.shortLanguage("Hindi"))
        assertEquals("ur", TextUtil.shortLanguage("urd"))
        val url = BookWebSearch.links(" गुनाहों का देवता & author ").first().url
        assertTrue(url.endsWith("&+author").not())
        assertTrue(url.contains("%26"))
        assertTrue(BookWebSearch.links("  ").isEmpty())
    }
}
