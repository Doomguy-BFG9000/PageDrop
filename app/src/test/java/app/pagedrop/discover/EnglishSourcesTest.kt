package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class EnglishSourcesTest {
    @Test fun fadedPageSessionIsKeptOnlyForItsOwnHttpsDownloads() {
        val jar = FadedPageCookieJar()
        val page = "https://www.fadedpage.com/showbook.php?pid=20181181".toHttpUrl()
        val cookie = Cookie.Builder().name("PHPSESSID").value("test-session").hostOnlyDomain("www.fadedpage.com").path("/").build()
        jar.saveFromResponse(page, listOf(cookie, cookie.newBuilderForTest("analytics")))
        assertEquals(listOf(cookie), jar.loadForRequest("https://www.fadedpage.com/link.php?file=20181181.epub".toHttpUrl()))
        assertTrue(jar.loadForRequest("https://other.example/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://www.fadedpage.com/".toHttpUrl()).isEmpty())
        jar.saveFromResponse(page, listOf(Cookie.Builder().name("PHPSESSID").value("").hostOnlyDomain("www.fadedpage.com").path("/").expiresAt(1).build()))
        assertTrue(jar.loadForRequest(page).isEmpty())
    }

    private fun Cookie.newBuilderForTest(name: String) = Cookie.Builder().name(name).value(value).hostOnlyDomain(domain).path(path).build()

    @Test fun oceanOfPdfIsAWebsiteShortcutWhenAutomatedAccessIsBlocked() {
        val link = BookWebSearch.links("Frankenstein", includeShadowLibraries = true).single { it.sourceName == "OceanofPDF" }
        assertEquals("https://oceanofpdf.com/", link.url)
        assertTrue(link.description.contains("Frankenstein"))
        assertTrue(link.description.contains("blocked"))
    }

    @Test fun shadowLibraryShortcutsAreHiddenUnlessTurnedOn() {
        val shadow = setOf("Anna’s Archive", "OceanofPDF")
        val hidden = BookWebSearch.links("Frankenstein").map { it.sourceName }
        assertTrue(hidden.none { it in shadow })
        assertTrue("Global Grey" in hidden)
        val shown = BookWebSearch.links("Frankenstein", includeShadowLibraries = true).map { it.sourceName }
        assertTrue(shown.containsAll(shadow))
    }

    @Test fun fadedPagePostsTheTitleInsteadOfFetchingAnUnfilteredCatalog() {
        val http = FakeHttp().apply { on("fadedpage.com/csearc2.php", fixture("fadedpage_search_gatsby.json")) }
        val book = FadedPageSource(http).search(" gatsby ").single()
        assertEquals("The Great Gatsby", book.title)
        assertEquals("F. Scott Fitzgerald", book.author)
        assertEquals("en", book.language)
        assertEquals("gatsby", http.postedForms.single().second["title"])
        assertTrue(book.needsResolve && book.options.isEmpty())
        assertEquals("https://www.fadedpage.com/showbook.php?pid=20181181", book.websiteUrl)
    }

    @Test fun fadedPageResolvesOnlyAdvertisedFilesForTheChosenEdition() = runBlocking {
        val http = FakeHttp().apply { on("showbook.php?pid=20181181", fixture("fadedpage_detail_gatsby.html")) }
        val book = FadedPageSource.parseSearch(fixture("fadedpage_search_gatsby.json")).single()
        val resolved = BookSearchEngine(newSettings(), http, 5_000).resolve(book)
        assertFalse(resolved.needsResolve)
        assertEquals(setOf(BookFormat.TXT, BookFormat.EPUB, BookFormat.MOBI, BookFormat.PDF), resolved.options.map { it.format }.toSet())
        assertEquals(BookFormat.MOBI, Ranking.bestOption(resolved.options)!!.format)
        assertEquals("https://www.fadedpage.com/link.php?file=20181181.epub", resolved.options.first { it.format == BookFormat.EPUB }.url)
        val unsafe = """<a href='https://other.example/link.php?file=20181181.epub'>Download</a>
            <a href='/link.php?file=99999999.epub'>Another book</a><a href='/link.php?file=20181181-h.zip'>HTML archive</a>"""
        assertTrue(FadedPageSource.parseOptions("20181181", unsafe).isEmpty())
    }

    @Test fun manyBooksParsesMatchesButDoesNotTurnRecommendationsIntoSearchResults() {
        val book = ManyBooksSource.parse(fixture("manybooks_search_frankenstein.html")).single()
        assertEquals("Frankenstein", book.title)
        assertEquals("Mary Wollstonecraft Shelley", book.author)
        assertEquals("https://manybooks.net/titles/frankenstein", book.websiteUrl)
        assertTrue(book.options.isEmpty() && !book.needsResolve)
        assertTrue(ManyBooksSource.parse(fixture("manybooks_search_empty.html")).isEmpty())
        assertTrue(runCatching { ManyBooksSource.parse("<html>Sign in</html>") }.exceptionOrNull() is SourceUnavailableException)
    }

    @Test fun englishWikisourceUsesEnglishHostAndSeparateIds() {
        val books = WikisourceSource.parse(fixture("wikisource_search_frankenstein.json"), SourceKind.ENGLISH_WIKISOURCE)
        assertTrue(books.isNotEmpty())
        assertTrue(books.all { it.language == "en" && it.id.startsWith("english_wikisource:") })
        assertTrue(books.all { it.websiteUrl!!.startsWith("https://en.wikisource.org/wiki/") && '/' !in it.title })
    }

    @Test fun gutenbergWebsiteFallbackSurvivesGutendexTimeout() = runBlocking {
        val http = FakeHttp().apply {
            fail("gutendex.com", SocketTimeoutException("mirror timed out"))
            on("gutenberg.org/ebooks/search/", fixture("gutenberg_website_search.html"))
            on("gutenberg.org/ebooks/84", fixture("gutenberg_website_detail.html"))
        }
        val book = GutenbergSource(http).search("frankenstein").first { it.id == "gutenberg:84" }
        assertTrue(book.needsResolve)
        val resolved = BookSearchEngine(newSettings(), http, 5_000).resolve(book)
        assertFalse(resolved.needsResolve)
        assertEquals(setOf(BookFormat.AZW3, BookFormat.MOBI, BookFormat.EPUB, BookFormat.TXT), resolved.options.map { it.format }.toSet())
        assertTrue(resolved.options.first { it.format == BookFormat.EPUB }.url.endsWith("84.epub.images"))
        assertTrue(resolved.options.none { "/send/" in it.url || it.url.endsWith(".zip") })
    }

    @Test fun newEnglishSourcesAreSearchedAndCanBeDisabled() = runBlocking {
        val kinds = setOf(SourceKind.FADED_PAGE, SourceKind.MANYBOOKS, SourceKind.ENGLISH_WIKISOURCE)
        val settings = newSettings(allSources = true).apply {
            SourceKind.entries.filter { it !in kinds }.forEach { setEnabled(it, false) }
        }
        val http = FakeHttp().apply {
            on("fadedpage.com/csearc2.php", fixture("fadedpage_search_gatsby.json"))
            on("manybooks.net/search-book", fixture("manybooks_search_frankenstein.html"))
            on("en.wikisource.org", fixture("wikisource_search_frankenstein.json"))
        }
        val engine = BookSearchEngine(settings, http, 5_000)
        val result = engine.search("gatsby")
        assertTrue(result.errors.isEmpty())
        assertEquals(kinds, result.results.map { it.source }.toSet())
        assertTrue(result.websiteSearches.any { it.sourceName == "Global Grey" })
        kinds.forEach { settings.setEnabled(it, false) }
        http.requests.clear()
        assertTrue(engine.search("gatsby").results.isEmpty())
        assertTrue(http.requests.isEmpty())
    }
}
