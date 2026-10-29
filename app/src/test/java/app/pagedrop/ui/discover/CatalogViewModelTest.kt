package app.pagedrop.ui.discover

import app.pagedrop.discover.CatalogItem
import app.pagedrop.discover.CatalogPage
import app.pagedrop.discover.CatalogPageKind
import app.pagedrop.discover.CatalogSearch
import app.pagedrop.discover.FakeHttp
import app.pagedrop.discover.OpdsCatalog
import app.pagedrop.discover.OpdsClient
import app.pagedrop.discover.fixture
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val root = "https://books.example.com/opds/browse/"

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun shelfHttp() = FakeHttp().apply {
        on("books.example.com/opds/browse/novels?page=2", fixture("opds_browse_novels_p2.xml"))
        on("books.example.com/opds/browse/novels", fixture("opds_browse_novels_p1.xml"))
        on("books.example.com/opds/browse/fiction", fixture("opds_browse_fiction.xml"))
        on("books.example.com/opds/browse/staff", fixture("opds_browse_mixed.xml"))
        on("books.example.com/opds/browse/osd.xml", fixture("opds_browse_osd.xml"))
        on("books.example.com/opds/browse/find", fixture("opds_browse_search_menu.xml"))
        on("books.example.com/opds/browse/", fixture("opds_browse_root.xml"))
    }

    /** The real client over canned HTTP; [failNext] makes the next fetch of that address throw once. */
    private class ClientBrowser(private val client: OpdsClient) : CatalogBrowser {
        val failOnce = mutableSetOf<String>()
        override fun catalogName(catalogId: String) = "Example Shelf"
        private fun guard(url: String) { if (failOnce.remove(url)) throw IOException("boom") }
        override suspend fun open(catalogId: String, url: String?): CatalogPage {
            val target = url ?: client.catalog.url
            guard(target)
            return client.page(target)
        }
        override suspend fun search(catalogId: String, link: CatalogSearch?, query: String) = client.search(link, query)
    }

    private fun newVm(http: FakeHttp): Pair<CatalogViewModel, ClientBrowser> {
        val catalog = OpdsCatalog("cat", "Example Shelf", root)
        val browser = ClientBrowser(OpdsClient(catalog, null, http, mutableMapOf()))
        return CatalogViewModel(browser) to browser
    }

    private val CatalogLevel.folderTitles get() = items.filterIsInstance<CatalogItem.Folder>().map { it.title }
    private val CatalogLevel.bookTitles get() = items.filterIsInstance<CatalogItem.Book>().map { it.result.title }

    @Test
    fun navigatingDownTwoLevelsAndBackRestoresEachLevel() = runTest(dispatcher) {
        val (vm, _) = newVm(shelfHttp())
        vm.start("cat", null)
        advanceUntilIdle()
        assertEquals(LevelStatus.Ready, vm.state.value.current!!.status)
        assertEquals("Example Shelf", vm.state.value.current!!.title)
        assertEquals(listOf("Fiction", "Poetry"), vm.state.value.current!!.folderTitles)
        assertTrue(vm.state.value.atRoot)

        vm.openFolder(vm.state.value.current!!.items.first() as CatalogItem.Folder)
        advanceUntilIdle()
        assertEquals(2, vm.state.value.stack.size)
        // The title the user tapped, not the feed's own.
        assertEquals("Fiction", vm.state.value.current!!.title)
        vm.openFolder(vm.state.value.current!!.items.first() as CatalogItem.Folder)
        advanceUntilIdle()
        assertEquals("Novels", vm.state.value.current!!.title)
        assertEquals(listOf("The Lantern Keeper", "Salt and Paper"), vm.state.value.current!!.bookTitles)

        assertTrue(vm.back())
        assertEquals("Fiction", vm.state.value.current!!.title)
        assertEquals(listOf("Novels", "Short stories"), vm.state.value.current!!.folderTitles)
        assertTrue(vm.back())
        assertEquals("Example Shelf", vm.state.value.current!!.title)
        // At the root: nothing left to go back to, so the screen leaves.
        assertFalse(vm.back())
    }

    @Test
    fun goingBackDoesNotRefetchTheLevelAbove() = runTest(dispatcher) {
        val http = shelfHttp()
        val (vm, _) = newVm(http)
        vm.start("cat", null)
        advanceUntilIdle()
        vm.openFolder(vm.state.value.current!!.items.first() as CatalogItem.Folder)
        advanceUntilIdle()
        val requests = http.requests.size
        vm.back()
        advanceUntilIdle()
        assertEquals(requests, http.requests.size)
    }

    @Test
    fun startingTwiceKeepsTheStackAsRotationWould() = runTest(dispatcher) {
        val http = shelfHttp()
        val (vm, _) = newVm(http)
        vm.start("cat", null)
        advanceUntilIdle()
        vm.openFolder(vm.state.value.current!!.items.first() as CatalogItem.Folder)
        advanceUntilIdle()
        vm.start("cat", null) // the screen is recreated and asks again
        advanceUntilIdle()
        assertEquals(2, vm.state.value.stack.size)
    }

    @Test
    fun pagesAreAppendedAutomaticallyWithoutDuplicates() = runTest(dispatcher) {
        val http = shelfHttp()
        val (vm, _) = newVm(http)
        vm.start("cat", "https://books.example.com/opds/browse/novels")
        advanceUntilIdle()
        assertEquals(listOf("The Lantern Keeper", "Salt and Paper"), vm.state.value.current!!.bookTitles)

        vm.loadMore()
        advanceUntilIdle()
        // "Salt and Paper" is on both pages and appears once.
        assertEquals(
            listOf("The Lantern Keeper", "Salt and Paper", "Winter Orchard"),
            vm.state.value.current!!.bookTitles,
        )
        assertNull(vm.state.value.current!!.nextUrl)
        val requests = http.requests.size
        vm.loadMore() // no next page left
        advanceUntilIdle()
        assertEquals(requests, http.requests.size)
    }

    @Test
    fun aFailedNextPageOffersLoadMoreAndRetrySucceeds() = runTest(dispatcher) {
        val (vm, browser) = newVm(shelfHttp())
        vm.start("cat", "https://books.example.com/opds/browse/novels")
        advanceUntilIdle()
        browser.failOnce += "https://books.example.com/opds/browse/novels?page=2"

        vm.loadMore()
        advanceUntilIdle()
        var level = vm.state.value.current!!
        assertTrue(level.moreFailed)
        assertFalse(level.loadingMore)
        assertEquals(2, level.items.size) // what was loaded stays
        assertEquals(LevelStatus.Ready, level.status)

        vm.loadMore() // the "Load more" row
        advanceUntilIdle()
        level = vm.state.value.current!!
        assertFalse(level.moreFailed)
        assertEquals(3, level.items.size)
    }

    @Test
    fun mixedFeedShowsFoldersAndBooksInFeedOrder() = runTest(dispatcher) {
        val (vm, _) = newVm(shelfHttp())
        vm.start("cat", "https://books.example.com/opds/browse/staff")
        advanceUntilIdle()
        val level = vm.state.value.current!!
        assertEquals(CatalogPageKind.MIXED, level.kind)
        assertEquals(
            listOf("folder", "book", "folder", "book"),
            level.items.map { if (it is CatalogItem.Folder) "folder" else "book" },
        )
    }

    @Test
    fun searchAnsweringWithAMenuShowsTheChoicesAndMakesNoSubRequests() = runTest(dispatcher) {
        val http = shelfHttp()
        val (vm, _) = newVm(http)
        vm.start("cat", null)
        advanceUntilIdle()
        assertTrue(vm.state.value.canSearch)
        vm.setSearchOpen(true)
        assertTrue(vm.state.value.searchOpen)
        http.requests.clear()

        vm.search("lantern")
        advanceUntilIdle()

        val level = vm.state.value.current!!
        assertFalse(vm.state.value.searchOpen)
        assertEquals(2, vm.state.value.stack.size)
        assertEquals("Search results", level.title)
        assertEquals(listOf("Search authors", "Search books", "Search series"), level.folderTitles)
        assertEquals(CatalogPageKind.NAVIGATION, level.kind)
        // Description, then the search. The menu's own links were never opened.
        assertEquals(2, http.requests.size)
        assertTrue(http.requests.none { "/find/" in it.first })

        // Choosing one of them is the user's call.
        vm.back()
        assertEquals("Example Shelf", vm.state.value.current!!.title)
    }

    @Test
    fun searchingAgainReplacesTheEarlierSearchLevel() = runTest(dispatcher) {
        val (vm, _) = newVm(shelfHttp())
        vm.start("cat", null)
        advanceUntilIdle()
        vm.search("lantern")
        advanceUntilIdle()
        // The result feed has no search link of its own: the catalog root's is used again.
        assertTrue(vm.state.value.canSearch)
        vm.search("orchard")
        advanceUntilIdle()
        assertEquals(2, vm.state.value.stack.size)
    }

    @Test
    fun noSearchIconWhenTheCatalogOffersNone() = runTest(dispatcher) {
        val (vm, _) = newVm(shelfHttp())
        vm.start("cat", "https://books.example.com/opds/browse/fiction")
        advanceUntilIdle()
        assertFalse(vm.state.value.canSearch)
        vm.setSearchOpen(true)
        assertFalse(vm.state.value.searchOpen)
    }

    @Test
    fun backClosesTheSearchBoxBeforeGoingUp() = runTest(dispatcher) {
        val (vm, _) = newVm(shelfHttp())
        vm.start("cat", null)
        advanceUntilIdle()
        vm.openFolder(vm.state.value.current!!.items.first() as CatalogItem.Folder)
        advanceUntilIdle()
        vm.setSearchOpen(true)
        // Fiction's own feed has no search link, but the catalog root's applies.
        assertTrue(vm.state.value.searchOpen)
        assertTrue(vm.back())
        assertFalse(vm.state.value.searchOpen)
        assertEquals(2, vm.state.value.stack.size)
    }

    @Test
    fun anErrorIsDistinctFromAnEmptyFeedAndRefreshRecovers() = runTest(dispatcher) {
        val http = FakeHttp().apply { on("books.example.com", "", code = 503) }
        val (vm, _) = newVm(http)
        vm.start("cat", null)
        advanceUntilIdle()
        var level = vm.state.value.current!!
        assertEquals(LevelStatus.Failed, level.status)
        assertEquals("Example Shelf answered with an error (HTTP 503)", level.error)

        http.routes.clear()
        http.on("books.example.com", fixture("opds_browse_empty.xml"))
        vm.refresh()
        advanceUntilIdle()
        level = vm.state.value.current!!
        assertEquals(LevelStatus.Ready, level.status)
        assertTrue(level.items.isEmpty())
        assertNull(level.error)
    }

    @Test
    fun aSearchWithNoMatchesIsReadyAndEmpty() = runTest(dispatcher) {
        val http = shelfHttp().apply { routes.add(0, FakeHttp.Route("opds/browse/find") { app.pagedrop.discover.HttpReply(200, fixture("opds_browse_empty.xml"), "https://books.example.com/opds/browse/find?q=zzz", null) }) }
        val (vm, _) = newVm(http)
        vm.start("cat", null)
        advanceUntilIdle()
        vm.search("zzz")
        advanceUntilIdle()
        val level = vm.state.value.current!!
        assertEquals(LevelStatus.Ready, level.status)
        assertTrue(level.items.isEmpty())
        assertEquals("zzz", (level.request as CatalogRequest.Search).query)
    }
}
