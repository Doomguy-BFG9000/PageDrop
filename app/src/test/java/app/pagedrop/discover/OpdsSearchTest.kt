package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Global search over a user's OPDS catalogs: books inline, a menu answer is handed back (never followed). */
class OpdsSearchTest {

    private val catalog = OpdsCatalog("cat", "Example Catalog", "https://books.example.com/opds/")

    /** Template already discovered, so each search starts with the search request itself. */
    private fun source(http: FakeHttp) = OpdsSource(
        catalog, null, http, mutableMapOf(catalog.id to "https://books.example.com/opds/search?q={searchTerms}"),
    )

    private fun menuHttp() = FakeHttp().apply {
        on("books.example.com/opds/search?q=", fixture("opds_search_navigation.xml"))
        on("books.example.com/opds/search/books", fixture("opds_search_books.xml"))
        on("books.example.com/opds/search/authors", fixture("opds_search_authors.xml"))
    }

    @Test
    fun menuAnswerBecomesAMenuAndNothingIsFollowed() {
        val http = menuHttp()
        val outcome = source(http).searchCatalog("dune")
        assertTrue(outcome.results.isEmpty())
        val menu = outcome.menu!!
        assertEquals("cat", menu.catalogId)
        assertEquals("Example Catalog", menu.catalogName)
        assertEquals("https://books.example.com/opds/search?q=dune", menu.feedUrl)
        assertEquals(2, menu.optionCount)
        assertEquals(listOf("Search authors", "Search books"), menu.optionTitles)
        // Exactly the search request: no sub-requests to the menu's links.
        assertEquals(1, http.requests.size)
    }

    @Test
    fun menuTitleIsCountedAndSingularWhenOne() {
        val one = CatalogMenu("c", "Shelf", "https://books.example.com/x", 1, listOf("Search books"))
        val three = one.copy(optionCount = 3)
        assertEquals("Shelf: 1 search option", app.pagedrop.ui.discover.catalogMenuTitle(one))
        assertEquals("Shelf: 3 search options", app.pagedrop.ui.discover.catalogMenuTitle(three))
    }

    @Test
    fun acquisitionSearchResponseIsUsedDirectly() {
        val http = FakeHttp().apply { on("books.example.com/opds/search?q=", fixture("opds_search.xml")) }
        val outcome = source(http).searchCatalog("frankenstein")
        assertEquals(listOf("Frankenstein; Or, The Modern Prometheus", "Plain PDF Book"), outcome.results.map { it.title })
        // Books were found, so the folder entry next to them is not offered as a menu either.
        assertNull(outcome.menu)
        assertEquals(1, http.requests.size)
    }

    @Test
    fun searchStillReturnsPlainBooksForCallersThatOnlyWantBooks() {
        val http = FakeHttp().apply { on("books.example.com/opds/search?q=", fixture("opds_search_books.xml")) }
        val results = source(http).search("dune")
        assertEquals(listOf("Dune", "Dune Messiah"), results.map { it.title })
        assertEquals("opds:cat:urn:example:book:1", results[0].id)
        assertEquals("https://books.example.com/files/1.epub", results[0].options.single().url)
    }

    @Test
    fun emptyFeedIsNoResultsNotAnError() = runBlocking {
        val http = FakeHttp().apply {
            on("lib.example.org/opds/search", fixture("opds_search_empty.xml"))
            on("lib.example.org/opds/osd.xml", fixture("opds_osd.xml"))
            on("lib.example.org/opds/", fixture("opds_root.xml"))
        }
        val settings = newSettings(http)
        settings.addCatalog("https://lib.example.org/opds/", null, null).getOrThrow()
        SourceKind.entries.filter { it != SourceKind.OPDS }.forEach { settings.setEnabled(it, false) }
        val res = BookSearchEngine(settings, http, 5_000).search("nothing like this")
        assertTrue(res.results.isEmpty())
        assertTrue(res.errors.isEmpty())
        assertTrue(res.catalogMenus.isEmpty())
    }

    @Test
    fun engineSearchWithAMenuAnswerShowsTheMenuAndMakesNoSubRequests() = runBlocking {
        val http = FakeHttp().apply {
            on("lib.example.org/opds/search/books", fixture("opds_search_books.xml"))
            on("lib.example.org/opds/search/authors", fixture("opds_search_authors.xml"))
            on("lib.example.org/opds/search", fixture("opds_search_navigation.xml"))
            on("lib.example.org/opds/osd.xml", fixture("opds_osd.xml"))
            on("lib.example.org/opds/", fixture("opds_root.xml"))
        }
        val settings = newSettings(http)
        val catalog = settings.addCatalog("https://lib.example.org/opds/", null, null).getOrThrow()
        SourceKind.entries.filter { it != SourceKind.OPDS }.forEach { settings.setEnabled(it, false) }
        val engine = BookSearchEngine(settings, http, 5_000)
        engine.warmUp()
        val before = http.requests.size

        val res = engine.search("dune")

        assertTrue(res.results.isEmpty())
        assertTrue(res.errors.isEmpty())
        val menu = res.catalogMenus.single()
        assertEquals(catalog.id, menu.catalogId)
        assertEquals(2, menu.optionCount)
        val sent = http.requests.drop(before).map { it.first }
        assertEquals(1, sent.size)
        assertTrue(sent.none { "/search/books" in it || "/search/authors" in it })
    }

    @Test
    fun engineSearchShowsBooksFromOneCatalogAndAMenuFromAnother() = runBlocking {
        val http = FakeHttp().apply {
            on("books.example.com/opds/search?q=", fixture("opds_search_books.xml"))
            on("lib.example.org/opds/search", fixture("opds_search_navigation.xml"))
            on("lib.example.org/opds/osd.xml", fixture("opds_osd.xml"))
            on("lib.example.org/opds/", fixture("opds_root.xml"))
            on("books.example.com/opds/osd.xml", fixture("opds_osd.xml").replace("/opds/search", "https://books.example.com/opds/search"))
            on("books.example.com/opds/", fixture("opds_root.xml"))
        }
        val settings = newSettings(http)
        settings.addCatalog("https://books.example.com/opds/", null, null).getOrThrow()
        settings.addCatalog("https://lib.example.org/opds/", null, null).getOrThrow()
        SourceKind.entries.filter { it != SourceKind.OPDS }.forEach { settings.setEnabled(it, false) }
        val res = BookSearchEngine(settings, http, 5_000).search("dune")
        assertEquals(listOf("Dune", "Dune Messiah"), res.results.map { it.title })
        assertEquals(1, res.catalogMenus.size)
    }

    @Test
    fun warmUpFindsTheSearchAddressSoTheFirstSearchSkipsIt() = runBlocking {
        val http = FakeHttp().apply {
            on("lib.example.org/opds/search", fixture("opds_search_empty.xml"))
            on("lib.example.org/opds/osd.xml", fixture("opds_osd.xml"))
            on("lib.example.org/opds/", fixture("opds_root.xml"))
        }
        val settings = newSettings(http)
        settings.addCatalog("https://lib.example.org/opds/", null, null).getOrThrow()
        SourceKind.entries.filter { it != SourceKind.OPDS }.forEach { settings.setEnabled(it, false) }
        val engine = BookSearchEngine(settings, http, 5_000)

        engine.warmUp()
        val before = http.requests.size
        engine.search("anything")

        val searchRequests = http.requests.drop(before).map { it.first }
        assertEquals(1, searchRequests.size)
        assertTrue(searchRequests.single().contains("lib.example.org/opds/search"))
    }

    @Test
    fun aSingleBooksFeedListingItTwiceShowsOnceWithEveryDownload() {
        val http = FakeHttp().apply { on("books.example.com/opds/book/1342.opds", fixture("opds_book_1342.xml")) }
        val client = OpdsClient(catalog, null, http, mutableMapOf())
        val page = client.page("https://books.example.com/opds/book/1342.opds")
        val book = (page.items.single() as CatalogItem.Book).result
        // Two packagings listed (two ids) become one book; the first listed wins per format.
        assertEquals("urn:example:1342:2", book.id.removePrefix("opds:cat:"))
        assertEquals("https://books.example.com/files/1342.kindle.noimages", book.options.single { it.format == BookFormat.MOBI }.url)
        assertEquals("https://books.example.com/files/1342.epub.noimages", book.options.single { it.format == BookFormat.EPUB }.url)
        assertNotNull(book)
    }

    @Test
    fun aSearchAnswerThatLinksToBookFeedsIsAMenuOfThoseBooks() {
        val http = FakeHttp().apply { on("books.example.com/opds/search?q=", fixture("opds_search_gutenberg_nav.xml")) }
        val outcome = source(http).searchCatalog("pride")
        assertEquals(2, outcome.menu!!.optionCount)
        assertEquals(1, http.requests.size)
    }
}
