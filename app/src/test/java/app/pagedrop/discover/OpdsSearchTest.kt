package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class OpdsSearchTest {

    private val catalog = OpdsCatalog("cat", "Example Catalog", "https://books.example.com/opds/")

    /** Template already discovered, so each search starts with the search request itself. */
    private fun source(http: FakeHttp) = OpdsSource(
        catalog, null, http, mutableMapOf(catalog.id to "https://books.example.com/opds/search?q={searchTerms}"),
    )

    private fun navigationHttp() = FakeHttp().apply {
        on("books.example.com/opds/search/books", fixture("opds_search_books.xml"))
        on("books.example.com/opds/search/authors", fixture("opds_search_authors.xml"))
        on("books.example.com/opds/search?q=", fixture("opds_search_navigation.xml"))
    }

    @Test
    fun navigationLinksPutBookSearchBeforeAuthorSearch() {
        val feed = OpdsParser.parseFeed(fixture("opds_search_navigation.xml"), "https://books.example.com/opds/search?q=dune")
        assertEquals(
            listOf("https://books.example.com/opds/search/books?q=dune", "https://books.example.com/opds/search/authors?q=dune"),
            OpdsParser.navigationLinks(feed),
        )
    }

    @Test
    fun navigationSearchResponseIsFollowedOneLevel() {
        val http = navigationHttp()
        val results = source(http).search("dune")
        assertEquals(listOf("Dune", "Dune Messiah"), results.map { it.title })
        assertEquals("https://books.example.com/files/1.epub", results[0].options.single().url)
        assertEquals("https://books.example.com/covers/1.jpg", results[0].coverUrl)
        assertEquals("opds:cat:urn:example:book:1", results[0].id)
        // One level only: the author entry's own feed isn't opened.
        assertTrue(http.requests.none { "/opds/author/7" in it.first })
    }

    @Test
    fun acquisitionSearchResponseIsUsedDirectly() {
        val http = FakeHttp().apply { on("books.example.com/opds/search?q=", fixture("opds_search.xml")) }
        val results = source(http).search("frankenstein")
        assertEquals(listOf("Frankenstein; Or, The Modern Prometheus", "Plain PDF Book"), results.map { it.title })
        // Books were found, so the folder entry next to them isn't followed.
        assertEquals(1, http.requests.size)
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
    }

    @Test
    fun navigationLeadingNowhereIsNoResultsNotAnError() {
        val http = FakeHttp().apply {
            on("books.example.com/opds/search/books", fixture("opds_search_empty.xml"))
            on("books.example.com/opds/search/authors", fixture("opds_search_authors.xml"))
            on("books.example.com/opds/search?q=", fixture("opds_search_navigation.xml"))
        }
        assertTrue(source(http).search("dune").isEmpty())
    }

    @Test
    fun failingNavigationLinkDoesNotHideTheOthers() {
        val http = FakeHttp().apply {
            fail("books.example.com/opds/search/authors", UnknownHostException("books.example.com"))
            on("books.example.com/opds/search/books", fixture("opds_search_books.xml"))
            on("books.example.com/opds/search?q=", fixture("opds_search_navigation.xml"))
        }
        assertEquals(listOf("Dune", "Dune Messiah"), source(http).search("dune").map { it.title })
    }

    @Test
    fun everyNavigationLinkFailingIsReportedAsUnavailable() {
        val http = FakeHttp().apply {
            on("books.example.com/opds/search/authors", "", code = 500)
            on("books.example.com/opds/search/books", "", code = 503)
            on("books.example.com/opds/search?q=", fixture("opds_search_navigation.xml"))
        }
        val error = runCatching { source(http).search("dune") }.exceptionOrNull()
        assertTrue(error is SourceUnavailableException)
        assertTrue(error!!.message!!.contains("HTTP"))
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
}
