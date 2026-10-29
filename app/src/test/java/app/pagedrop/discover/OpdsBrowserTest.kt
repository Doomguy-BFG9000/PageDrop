package app.pagedrop.discover

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** The OPDS browse client: one feed page at a time, search answers shown as they are, nothing auto-followed. */
class OpdsBrowserTest {

    private val catalog = OpdsCatalog("cat", "Example Shelf", "https://books.example.com/opds/browse/")
    private val root = "https://books.example.com/opds/browse/"

    private fun shelfHttp() = FakeHttp().apply {
        on("books.example.com/opds/browse/novels?page=2", fixture("opds_browse_novels_p2.xml"))
        on("books.example.com/opds/browse/novels", fixture("opds_browse_novels_p1.xml"))
        on("books.example.com/opds/browse/fiction", fixture("opds_browse_fiction.xml"))
        on("books.example.com/opds/browse/staff", fixture("opds_browse_mixed.xml"))
        on("books.example.com/opds/browse/osd.xml", fixture("opds_browse_osd.xml"))
        on("books.example.com/opds/browse/find", fixture("opds_browse_search_menu.xml"))
        on("books.example.com/opds/browse/", fixture("opds_browse_root.xml"))
    }

    private fun client(http: DiscoverHttp, auth: BasicAuth? = null, cache: MutableMap<String, String> = mutableMapOf()) =
        OpdsClient(catalog, auth, http, cache)

    private fun CatalogPage.folders() = items.filterIsInstance<CatalogItem.Folder>()
    private fun CatalogPage.books() = items.filterIsInstance<CatalogItem.Book>().map { it.result }

    @Test
    fun navigationFeedListsItsEntriesAsFoldersAndSkipsLinksThatLeadNowhere() {
        val page = client(shelfHttp()).page(root)
        assertEquals("Example Shelf", page.title)
        assertEquals(CatalogPageKind.NAVIGATION, page.kind)
        // "About this shelf" only links to an HTML page: not a way further into the catalog.
        assertEquals(listOf("Fiction", "Poetry"), page.folders().map { it.title })
        val fiction = page.folders().first()
        assertEquals("https://books.example.com/opds/browse/fiction", fiction.url)
        assertEquals("Novels and short stories, from the invented to the imagined.", fiction.summary)
        assertEquals("https://books.example.com/img/fiction-thumb.png", fiction.thumbnailUrl)
        assertNull(page.folders()[1].summary)
        assertEquals(CatalogSearch("https://books.example.com/opds/browse/osd.xml", isDescription = true), page.search)
        assertNull(page.nextUrl)
    }

    @Test
    fun choosingANavigationEntryOpensItsAcquisitionFeed() {
        val http = shelfHttp()
        val c = client(http)
        val fiction = c.page(root).folders().first()
        val novels = c.page(c.page(fiction.url).folders().first().url)
        assertEquals(CatalogPageKind.ACQUISITION, novels.kind)
        assertEquals(listOf("The Lantern Keeper", "Salt and Paper"), novels.books().map { it.title })
        val first = novels.books().first()
        assertEquals("Ada Marsh", first.author)
        assertEquals("https://books.example.com/covers/n1.jpg", first.coverUrl)
        assertEquals(setOf(BookFormat.EPUB, BookFormat.MOBI), first.options.map { it.format }.toSet())
        assertEquals("opds:cat:urn:example:novel:1", first.id)
    }

    @Test
    fun nestedNavigationTwoLevelsDeepResolvesEachLevelFromTheLevelAbove() {
        val http = shelfHttp()
        val c = client(http)
        val level1 = c.page(root)
        val level2 = c.page(level1.folders()[0].url)
        assertEquals("Fiction shelf", level2.title)
        assertEquals(listOf("Novels", "Short stories"), level2.folders().map { it.title })
        // Only the pages the user opened were requested, one each.
        assertEquals(listOf(root, "https://books.example.com/opds/browse/fiction"), http.requests.map { it.first })
    }

    @Test
    fun paginatedFeedHasANextLinkOnTheFirstPageOnly() {
        val c = client(shelfHttp())
        val p1 = c.page("https://books.example.com/opds/browse/novels")
        assertEquals("https://books.example.com/opds/browse/novels?page=2", p1.nextUrl)
        val p2 = c.page(p1.nextUrl!!)
        assertNull(p2.nextUrl)
        // The server repeats one book across pages; the view layer de-duplicates (see CatalogViewModelTest).
        assertEquals(listOf("Salt and Paper", "Winter Orchard"), p2.books().map { it.title })
    }

    @Test
    fun mixedFeedKeepsFeedOrderAndMarksUnusableBooksWithoutOptions() {
        val page = client(shelfHttp()).page("https://books.example.com/opds/browse/staff")
        assertEquals(CatalogPageKind.MIXED, page.kind)
        assertEquals(
            listOf("More picks", "A Quiet Harbour", "By author", "Borrow-only Title"),
            page.items.map { if (it is CatalogItem.Folder) it.title else (it as CatalogItem.Book).result.title },
        )
        assertEquals(listOf(BookFormat.EPUB), page.books()[0].options.map { it.format })
        assertTrue(page.books()[1].options.isEmpty()) // borrow links are not free downloads
    }

    @Test
    fun feedWithNoEntriesIsEmptyNotAnError() {
        val http = FakeHttp().apply { on("books.example.com", fixture("opds_browse_empty.xml")) }
        val page = client(http).page(root)
        assertEquals(CatalogPageKind.EMPTY, page.kind)
        assertTrue(page.items.isEmpty())
    }

    @Test
    fun serverErrorIsUnavailableWithTheReason() {
        val http = FakeHttp().apply { on("books.example.com", "", code = 503) }
        val e = runCatching { client(http).page(root) }.exceptionOrNull()
        assertTrue(e is SourceUnavailableException)
        assertEquals("Example Shelf answered with an error (HTTP 503)", e!!.message)
    }

    @Test
    fun somethingThatIsNotACatalogIsUnavailable() {
        val http = FakeHttp().apply { on("books.example.com", "<html><body>hello</body></html>") }
        val e = runCatching { client(http).page(root) }.exceptionOrNull()
        assertTrue(e is SourceUnavailableException)
    }

    @Test
    fun searchAnsweringWithAMenuReturnsTheChoicesAndFollowsNone() {
        val http = shelfHttp()
        val c = client(http)
        val link = c.page(root).search
        http.requests.clear()

        val answer = c.search(link, "lantern")

        assertEquals(CatalogPageKind.NAVIGATION, answer.kind)
        assertEquals(listOf("Search authors", "Search books", "Search series"), answer.folders().map { it.title })
        assertEquals("3 authors", answer.folders()[0].summary)
        // Exactly two requests: the OpenSearch description, then the search itself. None of the menu's links.
        assertEquals(
            listOf("https://books.example.com/opds/browse/osd.xml", "https://books.example.com/opds/browse/find?q=lantern"),
            http.requests.map { it.first },
        )
    }

    @Test
    fun searchTemplateIsRememberedSoASecondSearchSkipsTheDescription() {
        val http = shelfHttp()
        val cache = mutableMapOf<String, String>()
        val c = client(http, cache = cache)
        val link = c.page(root).search
        c.search(link, "lantern")
        http.requests.clear()
        c.search(link, "orchard")
        assertEquals(listOf("https://books.example.com/opds/browse/find?q=orchard"), http.requests.map { it.first })
    }

    @Test
    fun aFeedsInlineSearchTemplateIsUsedWithoutAnyDescriptionRequest() {
        val feed = """<feed xmlns="http://www.w3.org/2005/Atom"><title>T</title>
            <link rel="search" type="application/atom+xml" href="/opds/browse/find?q={searchTerms}"/></feed>"""
        val http = shelfHttp().apply { routes.add(0, FakeHttp.Route("books.example.com/opds/browse/inline") { HttpReply(200, feed, "https://books.example.com/opds/browse/inline", null) }) }
        val c = client(http)
        val link = c.page("https://books.example.com/opds/browse/inline").search!!
        assertEquals(false, link.isDescription)
        http.requests.clear()
        c.search(link, "x y")
        assertEquals(listOf("https://books.example.com/opds/browse/find?q=x+y"), http.requests.map { it.first })
    }

    @Test
    fun credentialsGoOnlyToTheCatalogsOwnHost() {
        val auth = BasicAuth("sam", "pw")
        val http = shelfHttp().apply {
            on("cdn.other.example.net/feed", fixture("opds_browse_empty.xml"))
        }
        val c = client(http, auth)
        c.page(root)
        c.page("https://books.example.com/opds/browse/fiction")
        c.page("https://cdn.other.example.net/feed")
        val byHost = http.requests.groupBy({ it.first.substringAfter("://").substringBefore('/') }, { it.second })
        assertTrue(byHost.getValue("books.example.com").all { it == auth })
        assertEquals(listOf<BasicAuth?>(null), byHost.getValue("cdn.other.example.net"))
    }

    @Test
    fun plainHttpAddressesAreRequestedOverHttps() {
        val seen = mutableListOf<String>()
        val okhttp = OkHttpClient.Builder().addInterceptor { chain ->
            val url = chain.request().url.toString()
            seen += url
            val body = if ("page=2" in url) fixture("opds_browse_novels_p2.xml")
            else fixture("opds_browse_novels_p1.xml").replace("/opds/browse/novels?page=2", "http://books.example.com/opds/browse/novels?page=2")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/atom+xml".toMediaType())).build()
        }.build()
        val c = OpdsClient(
            OpdsCatalog("cat", "Example Shelf", "http://books.example.com/opds/browse/"), null,
            OkHttpDiscoverHttp(5_000, okhttp), mutableMapOf(),
        )
        val p1 = c.page("http://books.example.com/opds/browse/novels")
        c.page(p1.nextUrl!!)
        assertEquals(
            listOf("https://books.example.com/opds/browse/novels", "https://books.example.com/opds/browse/novels?page=2"),
            seen,
        )
    }

    private suspend fun engineWith(http: FakeHttp): Pair<BookSearchEngine, OpdsCatalog> {
        val settings = newSettings(http)
        val added = settings.addCatalog("https://books.example.com/opds/browse/", null, null).getOrThrow()
        return BookSearchEngine(settings, http, 5_000) to added
    }

    @Test
    fun engineBrowseAndSearchAnswerWithPagesAndMenus() = kotlinx.coroutines.runBlocking {
        val (engine, added) = engineWith(shelfHttp())
        val rootPage = engine.browseCatalog(added.id)
        assertEquals("Example Shelf", rootPage.title)
        val menu = engine.searchCatalog(added.id, rootPage.search, "  lantern ")
        assertEquals(3, menu.folders().size)
        assertEquals(CatalogPageKind.ACQUISITION, engine.browseCatalog(added.id, "https://books.example.com/opds/browse/novels").kind)
    }

    @Test
    fun engineBrowseReportsOfflineAndErrorsInPlainWords() = kotlinx.coroutines.runBlocking {
        val http = shelfHttp()
        val (engine, added) = engineWith(http)
        http.routes.clear()
        http.fail("books.example.com", UnknownHostException("books.example.com"))
        val offline = runCatching { engine.browseCatalog(added.id) }.exceptionOrNull() as SourceUnavailableException
        assertTrue(offline.offline)
        assertEquals("Example Shelf can't be reached. Check your connection.", offline.message)

        http.routes.clear()
        http.on("books.example.com", "", code = 500)
        val down = runCatching { engine.browseCatalog(added.id) }.exceptionOrNull() as SourceUnavailableException
        assertEquals("Example Shelf answered with an error (HTTP 500)", down.message)

        val gone = runCatching { engine.browseCatalog("nope") }.exceptionOrNull() as SourceUnavailableException
        assertEquals("This catalog is no longer in your book sources", gone.message)
    }

    @Test
    fun unreachableCatalogSurfacesTheNetworkError() {
        val http = FakeHttp().apply { fail("books.example.com", UnknownHostException("books.example.com")) }
        val e = runCatching { client(http).page(root) }.exceptionOrNull()
        assertTrue(e is UnknownHostException)
    }
}
