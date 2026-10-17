package app.pagedrop.discover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergSourceTest {
    @Test
    fun parsesFrankensteinWithKindleOptions() {
        val results = GutenbergSource.parse(fixture("gutendex_frankenstein.json"))
        val book = results.first { it.id == "gutenberg:84" }
        assertEquals("Mary Wollstonecraft Shelley", book.author)
        assertEquals("Project Gutenberg", book.sourceName)
        assertEquals("en", book.language)
        assertEquals("https://www.gutenberg.org/cache/epub/84/pg84.cover.medium.jpg", book.coverUrl)
        assertFalse(book.needsResolve)
        assertFalse(book.description!!.contains("automatically generated"))
        val byFormat = book.options.associateBy { it.format }
        assertEquals("https://www.gutenberg.org/ebooks/84.kf8.images", byFormat[BookFormat.AZW3]!!.url)
        assertEquals("https://www.gutenberg.org/ebooks/84.kindle.images", byFormat[BookFormat.MOBI]!!.url)
        // EPUB 3 is swapped for the more convertible EPUB 2 rendition.
        assertEquals("https://www.gutenberg.org/ebooks/84.epub.images", byFormat[BookFormat.EPUB]!!.url)
        assertTrue(byFormat[BookFormat.TXT]!!.url.endsWith("84.txt.utf-8"))
    }

    @Test
    fun booksWithoutFormatsStillParseWithNoOptions() {
        val results = GutenbergSource.parse(fixture("gutendex_pride_and_prejudice.json"))
        assertTrue(results.any { it.id == "gutenberg:1342" && it.options.isNotEmpty() })
        assertEquals("Jane Austen", results.first().author)
    }

    @Test
    fun invertsAuthorNamesAndDropsParentheticals() {
        val gatsby = GutenbergSource.parse(fixture("gutendex_great_gatsby.json")).single()
        assertEquals("F. Scott Fitzgerald", gatsby.author)
        assertEquals("The Great Gatsby", gatsby.title)
    }

    @Test
    fun skipsCopyrightedAndNonTextItems() {
        val body = """{"results":[
          {"id":1,"title":"Copyrighted","authors":[],"copyright":true,"formats":{},"media_type":"Text"},
          {"id":2,"title":"Audio","authors":[],"copyright":false,"formats":{},"media_type":"Sound"},
          {"id":3,"title":"Fine","authors":[{"name":"Doe, Jane"}],"copyright":false,"formats":{},"media_type":"Text"}]}"""
        assertEquals(listOf("gutenberg:3"), GutenbergSource.parse(body).map { it.id })
    }

    @Test
    fun classicMobiLinkStaysMobi() {
        val opts = GutenbergSource.options("5", mapOf("application/x-mobipocket-ebook" to "https://www.gutenberg.org/ebooks/5.kindle.images"))
        assertEquals(listOf(BookFormat.MOBI), opts.map { it.format })
    }

    @Test(expected = SourceUnavailableException::class)
    fun garbageBodyIsASourceError() {
        GutenbergSource.parse("<html>nope</html>")
    }

    @Test
    fun searchRequestsGutendex() {
        val http = FakeHttp().apply { on("gutendex.com/books/?search=great%20gatsby".replace("%20", "+"), fixture("gutendex_great_gatsby.json")) }
        assertEquals(1, GutenbergSource(http).search("great gatsby").size)
    }
}

class StandardEbooksSourceTest {
    @Test
    fun parsesSearchPageAndBuildsDownloadLinks() {
        val book = StandardEbooksSource.parse(fixture("standardebooks_search_frankenstein.html")).single()
        assertEquals("standard_ebooks:mary-shelley/frankenstein", book.id)
        assertEquals("Frankenstein", book.title)
        assertEquals("Mary Shelley", book.author)
        assertEquals("https://standardebooks.org/ebooks/mary-shelley/frankenstein/downloads/cover.jpg", book.coverUrl)
        val azw3 = book.options.first { it.format == BookFormat.AZW3 }
        assertEquals(
            "https://standardebooks.org/ebooks/mary-shelley/frankenstein/downloads/mary-shelley_frankenstein.azw3?source=download",
            azw3.url,
        )
        assertTrue(book.options.any { it.format == BookFormat.EPUB && it.url.endsWith(".epub?source=download") })
    }

    @Test
    fun translatorSlugsJoinAllSegmentsWithUnderscores() {
        val opts = StandardEbooksSource.options("fyodor-dostoevsky/demons/constance-garnett")
        assertTrue(opts.first().url.endsWith("/downloads/fyodor-dostoevsky_demons_constance-garnett.azw3?source=download"))
    }

    @Test
    fun noResultsPageGivesEmptyList() {
        assertTrue(StandardEbooksSource.parse(fixture("standardebooks_search_empty.html")).isEmpty())
    }

    @Test
    fun decodesHtmlEntitiesInTitles() {
        val html = """<ol><li typeof="schema:Book" about="/ebooks/a-b/c"><p><a href="/ebooks/a-b/c" property="schema:url"><span property="schema:name">Tom &amp; Jerry&#8217;s</span></a></p>
            <p class="author" typeof="schema:Person"><a href="x" property="schema:url"><span property="schema:name">A. B.</span></a></p></li></ol>"""
        assertEquals("Tom & Jerry’s", StandardEbooksSource.parse(html).single().title)
    }

    @Test
    fun ignoresUnsafeSlugs() {
        val html = """<li typeof="schema:Book" about="/ebooks/../../etc?x=1"><p><a href="/" property="schema:url"><span property="schema:name">Bad</span></a></p></li>"""
        assertTrue(StandardEbooksSource.parse(html).isEmpty())
    }
}

class InternetArchiveSourceTest {
    @Test
    fun parsesSearchIntoUnresolvedResults() {
        val results = InternetArchiveSource.parseSearch(fixture("ia_search_frankenstein.json"))
        assertTrue(results.all { it.needsResolve && it.options.isEmpty() && it.source == SourceKind.INTERNET_ARCHIVE })
        val ghost = results.first { it.id == "internet_archive:ghostseer01schiuoft" }
        assertEquals("Mary Wollstonecraft Shelley", ghost.author)
        assertEquals(1831, ghost.year)
        assertEquals("https://archive.org/services/img/ghostseer01schiuoft", ghost.coverUrl)
    }

    @Test
    fun dropsStandardEbooksMirrorFromSearch() {
        assertTrue(InternetArchiveSource.parseSearch(fixture("ia_search_frankenstein.json")).none { it.id.contains("mary-shelley_frankenstein") })
    }

    @Test
    fun metadataYieldsEpubAndTextPdfButNotGrayscalePdf() {
        val opts = InternetArchiveSource.parseMetadata("ghostseer01schiuoft", fixture("ia_metadata_open.json"))
        assertEquals(listOf(BookFormat.EPUB, BookFormat.PDF), opts.map { it.format })
        assertEquals("https://archive.org/download/ghostseer01schiuoft/ghostseer01schiuoft.epub", opts[0].url)
        assertEquals("https://archive.org/download/ghostseer01schiuoft/ghostseer01schiuoft.pdf", opts[1].url)
        assertEquals(2012388L, opts[0].sizeBytes)
    }

    @Test
    fun accessRestrictedItemsHaveNoOptions() {
        assertTrue(InternetArchiveSource.parseMetadata("bwb_T5-CVH-698", fixture("ia_metadata_restricted.json")).isEmpty())
    }

    @Test
    fun resolveFillsOptionsAndClearsFlag() {
        val http = FakeHttp().apply { on("archive.org/metadata/ghostseer01schiuoft", fixture("ia_metadata_open.json")) }
        val unresolved = InternetArchiveSource.parseSearch(fixture("ia_search_frankenstein.json")).first { it.id.endsWith("ghostseer01schiuoft") }
        val resolved = InternetArchiveSource(http).resolve(unresolved)
        assertFalse(resolved.needsResolve)
        assertEquals(2, resolved.options.size)
    }

    @Test
    fun sanitizeStripsLuceneOperators() {
        assertEquals("a b c", InternetArchiveSource.sanitize("a+b (c)"))
        assertEquals("pride and prejudice", InternetArchiveSource.sanitize("pride and prejudice"))
    }
}

class OpdsParserTest {
    @Test
    fun parsesAcquisitionEntriesAndIgnoresBuySampleAndFolders() {
        val feed = OpdsParser.parseFeed(fixture("opds_search.xml"), "https://lib.example.org/opds/search?q=x")
        val first = feed.entries.first()
        val options = OpdsParser.acquisitionOptions(first)
        assertEquals(listOf(BookFormat.EPUB, BookFormat.MOBI), options.map { it.format })
        assertEquals("https://lib.example.org/files/84.epub", options[0].url)
        assertEquals(474733L, options[0].sizeBytes)
        assertEquals("https://lib.example.org/covers/84.jpg", OpdsParser.coverOf(first))
        assertEquals(1818, first.year)
        assertEquals("A tale of ambition.", first.summary)
        assertTrue(OpdsParser.acquisitionOptions(feed.entries[1]).isEmpty()) // folder
        assertTrue(OpdsParser.acquisitionOptions(feed.entries[2]).isEmpty()) // cbz
    }

    @Test
    fun findsOpenSearchLinkAndFillsTemplate() {
        val feed = OpdsParser.parseFeed(fixture("opds_root.xml"), "https://lib.example.org/opds/")
        val link = feed.links.first { it.rel == "search" }
        assertEquals("https://lib.example.org/opds/osd.xml", link.href)
        val template = OpdsParser.parseOpenSearchTemplate(fixture("opds_osd.xml"))!!
        assertEquals("/opds/search?q=great+gatsby&page=1", OpdsParser.fillTemplate(template, "great gatsby"))
        assertEquals("Example Library", feed.title)
    }

    @Test
    fun jsonCatalogsAreRejectedWithAHelpfulMessage() {
        val e = runCatching { OpdsParser.parseFeed("""{"metadata":{"title":"x"}}""", "https://x") }.exceptionOrNull()
        assertTrue(e is SourceUnavailableException && e.message!!.contains("OPDS 2"))
    }

    @Test
    fun formatMapping() {
        assertEquals(BookFormat.AZW3, OpdsParser.formatOf("application/x-mobi8-ebook", "a"))
        assertEquals(BookFormat.AZW3, OpdsParser.formatOf("application/x-mobipocket-ebook", "a.azw3"))
        assertEquals(BookFormat.MOBI, OpdsParser.formatOf("application/x-mobipocket-ebook", "a.mobi"))
        assertEquals(BookFormat.PDF, OpdsParser.formatOf("application/octet-stream", "a.pdf?x=1"))
        assertNull(OpdsParser.formatOf("application/x-cbz", "a.cbz"))
    }

    @Test
    fun relativeSearchLinkKeepsPlaceholder() {
        assertEquals("https://h.example/opds/s?q={searchTerms}", OpdsParser.resolve("https://h.example/opds/", "s?q={searchTerms}"))
    }
}
