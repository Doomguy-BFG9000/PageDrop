package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

class BookSearchEngineTest {

    private fun result(
        id: String, source: SourceKind, title: String, author: String,
        vararg formats: BookFormat, needsResolve: Boolean = false,
    ) = BookResult(
        id, source, source.name, title, author,
        options = formats.map { DownloadOption(it, "https://x/$id.${it.extension}") }, needsResolve = needsResolve,
    )

    private fun frankensteinHttp() = FakeHttp().apply {
        on("gutendex.com", fixture("gutendex_frankenstein.json"))
        on("standardebooks.org/ebooks?query", fixture("standardebooks_search_frankenstein.html"))
        on("archive.org/advancedsearch", fixture("ia_search_frankenstein.json"))
    }

    @Test
    fun mergesSourcesAndPutsStandardEbooksFirstWithAzw3Best() = runBlocking {
        val engine = BookSearchEngine(newSettings(), frankensteinHttp(), 5_000)
        val res = engine.search("frankenstein")
        assertTrue(res.errors.isEmpty())
        val top = res.results.first()
        assertEquals(SourceKind.STANDARD_EBOOKS, top.source)
        assertEquals(BookFormat.AZW3, engine.bestOption(top)!!.format)
        // All four Gutenberg editions are the same book as the Standard Ebooks one, so they fold into it
        // (their TXT format is merged in) instead of cluttering the list.
        assertTrue(res.results.none { it.source == SourceKind.GUTENBERG })
        assertTrue(top.options.any { it.format == BookFormat.TXT })
        // A differently titled Internet Archive item stays, unresolved.
        assertTrue(res.results.any { it.source == SourceKind.INTERNET_ARCHIVE && it.needsResolve })
        assertEquals(res.results.size, res.results.map { it.id }.toSet().size)
    }

    @Test
    fun duplicateAcrossSourcesMergesFormatsIntoWinner() = runBlocking {
        val http = FakeHttp().apply {
            on("gutendex.com", fixture("gutendex_frankenstein.json"))
            on("standardebooks.org/ebooks?query", fixture("standardebooks_search_frankenstein.html"))
        }
        val settings = newSettings().apply { setEnabled(SourceKind.INTERNET_ARCHIVE, false) }
        val res = BookSearchEngine(settings, http, 5_000).search("frankenstein")
        val se = res.results.first { it.source == SourceKind.STANDARD_EBOOKS }
        // Gutenberg's plain-text edition fills a format SE lacks; SE's own AZW3/EPUB URLs stay.
        assertTrue(res.results.none { it.source == SourceKind.GUTENBERG })
        assertTrue(se.options.any { it.format == BookFormat.TXT })
        assertTrue(se.options.first { it.format == BookFormat.AZW3 }.url.contains("standardebooks.org"))
    }

    @Test
    fun oneFailingSourceDoesNotBlockOthers() = runBlocking {
        val http = FakeHttp().apply {
            fail("gutendex.com", UnknownHostException("gutendex.com"))
            on("standardebooks.org/ebooks?query", fixture("standardebooks_search_frankenstein.html"))
            on("archive.org/advancedsearch", "", code = 503)
        }
        val res = BookSearchEngine(newSettings(), http, 5_000).search("frankenstein")
        assertEquals(1, res.results.size)
        val byName = res.errors.associateBy { it.sourceName }
        assertTrue(byName["Project Gutenberg"]!!.offline)
        assertFalse(byName["Internet Archive"]!!.offline)
        assertTrue(byName["Internet Archive"]!!.message.contains("503"))
    }

    @Test
    fun slowSourceTimesOut() = runBlocking {
        val http = FakeHttp().apply {
            routes += FakeHttp.Route("gutendex.com") { Thread.sleep(1_500); HttpReply(200, "{}", "x", null) }
            on("standardebooks.org/ebooks?query", fixture("standardebooks_search_frankenstein.html"))
            on("archive.org/advancedsearch", fixture("ia_search_frankenstein.json"))
        }
        val res = BookSearchEngine(newSettings(), http, 300).search("frankenstein")
        assertTrue(res.errors.any { it.sourceName == "Project Gutenberg" && it.message.contains("too long") })
        assertTrue(res.results.isNotEmpty())
    }

    @Test
    fun timeoutInterruptsBlockingSourceInsteadOfWaitingForIt() = runBlocking {
        val settings = newSettings().apply {
            SourceKind.entries.filter { it != SourceKind.GUTENBERG }.forEach { setEnabled(it, false) }
        }
        val http = FakeHttp().apply {
            routes += FakeHttp.Route("gutendex.com") { Thread.sleep(5_000); HttpReply(200, "{}", "x", null) }
        }
        val started = System.nanoTime()
        val res = BookSearchEngine(settings, http, 100).search("गोदान")
        assertTrue(res.errors.single().message.contains("too long"))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun hindiTitlesKeepVowelsAndRankExactMatches() {
        assertEquals(listOf("गुनाहों", "का", "देवता"), TextUtil.words("गुनाहों का देवता"))
        assertFalse(TextUtil.normTitle("गोदान") == TextUtil.normTitle("गबन"))
        assertFalse(TextUtil.normTitle("दिन") == TextUtil.normTitle("दीन"))
        assertEquals("jose", TextUtil.words("José").single())
        val exact = result("1", SourceKind.HINDWI, "गोदान", "प्रेमचंद")
        val other = result("2", SourceKind.GUTENBERG, "गबन", "प्रेमचंद", BookFormat.MOBI)
        assertTrue(Ranking.score("गोदान", exact) > Ranking.score("गोदान", other))
    }

    @Test
    fun translationsAndUnresolvedEditionsAreNotLostInDedupe() = runBlocking {
        val http = FakeHttp().apply {
            on("standardebooks.org/ebooks?query", fixture("standardebooks_search_frankenstein.html"))
            on("gutendex.com", fixture("gutendex_frankenstein.json").replace("\"en\"", "\"hi\""))
            on("archive.org/advancedsearch", """{"response":{"docs":[
                {"identifier":"first","title":"Frankenstein","creator":"Mary Shelley","language":"en"},
                {"identifier":"second","title":"Frankenstein","creator":"Mary Shelley","language":"en"}
            ]}}""")
        }
        val results = BookSearchEngine(newSettings(), http, 5_000).search("frankenstein").results
        assertTrue(results.any { it.source == SourceKind.STANDARD_EBOOKS })
        assertTrue(results.any { it.source == SourceKind.GUTENBERG && it.language == "hi" })
        assertEquals(2, results.count { it.source == SourceKind.INTERNET_ARCHIVE })
    }

    @Test
    fun newSourcesParticipateAndWebSearchesAreSeparateFromBookResults() = runBlocking {
        val settings = newSettings(allSources = true).apply {
            setEnabled(SourceKind.GUTENBERG, false); setEnabled(SourceKind.STANDARD_EBOOKS, false)
            setEnabled(SourceKind.ENGLISH_WIKISOURCE, false); setEnabled(SourceKind.FADED_PAGE, false); setEnabled(SourceKind.MANYBOOKS, false)
        }
        val http = FakeHttp().apply {
            on("archive.org/advancedsearch", """{"response":{"docs":[{"identifier":"hindi-edition","title":"गोदान","creator":"प्रेमचंद","language":"hin"}]}}""")
            on("hindwi.org/search", fixture("hindwi_search_godaan.html"))
            on("rekhta.org/search", fixture("rekhta_search_raag.html"))
            on("hi.wikisource.org", fixture("wikisource_search_godaan.json"))
            on("openlibrary.org", fixture("openlibrary_search.json"))
        }
        val res = BookSearchEngine(settings, http, 5_000).search("गोदान")
        assertTrue(res.errors.isEmpty())
        assertTrue(res.results.any { it.source == SourceKind.HINDWI })
        assertTrue(res.results.any { it.source == SourceKind.REKHTA })
        assertTrue(res.results.any { it.source == SourceKind.HINDI_WIKISOURCE })
        assertTrue(res.results.any { it.source == SourceKind.OPEN_LIBRARY })
        assertEquals(1, res.results.count { it.archiveIdentifier == "hindi-edition" })
        assertTrue(http.requests.any { it.first.contains("collection%3Adigitallibraryindia") })
        assertEquals(listOf("ePustakalay", "Anna’s Archive", "Global Grey", "OceanofPDF"), res.websiteSearches.map { it.sourceName })
    }

    @Test
    fun disabledSourcesAreNotQueried() = runBlocking {
        val http = frankensteinHttp()
        val settings = newSettings().apply {
            setEnabled(SourceKind.GUTENBERG, false); setEnabled(SourceKind.INTERNET_ARCHIVE, false)
        }
        BookSearchEngine(settings, http, 5_000).search("frankenstein")
        assertEquals(1, http.requests.size)
        assertTrue(http.requests.single().first.contains("standardebooks.org"))
    }

    @Test
    fun blankQueryReturnsNothingWithoutNetwork() = runBlocking {
        val http = FakeHttp()
        val res = BookSearchEngine(newSettings(), http, 1_000).search("   ")
        assertTrue(res.results.isEmpty() && res.errors.isEmpty() && http.requests.isEmpty())
    }

    @Test
    fun resolveFillsInternetArchiveOptions() = runBlocking {
        val http = FakeHttp().apply { on("archive.org/metadata/ghostseer01schiuoft", fixture("ia_metadata_open.json")) }
        val engine = BookSearchEngine(newSettings(), http, 5_000)
        val ia = InternetArchiveSource.parseSearch(fixture("ia_search_frankenstein.json")).first { it.id.endsWith("ghostseer01schiuoft") }
        val resolved = engine.resolve(ia)
        assertFalse(resolved.needsResolve)
        assertEquals(BookFormat.EPUB, engine.bestOption(resolved)!!.format)
        // Already-resolved results are returned as is, no network.
        assertEquals(resolved, engine.resolve(resolved))
        assertEquals(1, http.requests.size)
    }

    @Test
    fun bestOptionPrefersKindleNativeThenEpubPdfTxt() {
        val engine = BookSearchEngine(newSettings(), FakeHttp(), 1_000)
        fun best(vararg f: BookFormat) = engine.bestOption(result("a", SourceKind.OPDS, "T", "A", *f))?.format
        assertEquals(BookFormat.AZW3, best(BookFormat.EPUB, BookFormat.MOBI, BookFormat.AZW3))
        assertEquals(BookFormat.MOBI, best(BookFormat.PDF, BookFormat.MOBI, BookFormat.EPUB))
        assertEquals(BookFormat.EPUB, best(BookFormat.TXT, BookFormat.PDF, BookFormat.EPUB))
        assertEquals(BookFormat.PDF, best(BookFormat.TXT, BookFormat.PDF))
        assertEquals(BookFormat.TXT, best(BookFormat.TXT))
        assertNull(best())
    }

    @Test
    fun rankingPrefersExactTitleThenKindleNative() {
        val exact = result("1", SourceKind.GUTENBERG, "Emma", "Jane Austen", BookFormat.EPUB)
        val partial = result("2", SourceKind.STANDARD_EBOOKS, "Emma and Other Stories", "Someone Else", BookFormat.AZW3)
        assertTrue(Ranking.score("emma", exact) > Ranking.score("emma", partial))
        val kindle = result("3", SourceKind.OPDS, "Emma", "Jane Austen", BookFormat.MOBI)
        assertTrue(Ranking.score("emma", kindle) > Ranking.score("emma", exact))
    }

    @Test
    fun dedupeKeyIgnoresSubtitleArticleAndAuthorGivenNames() {
        assertEquals(
            TextUtil.dedupeKey("Frankenstein", "Mary Shelley"),
            TextUtil.dedupeKey("Frankenstein; or, the modern prometheus", "Mary Wollstonecraft Shelley"),
        )
        assertEquals(
            TextUtil.dedupeKey("The Great Gatsby", "F. Scott Fitzgerald"),
            TextUtil.dedupeKey("Great Gatsby", "Fitzgerald, F. Scott"),
        )
    }

    @Test
    fun opdsCatalogIsSearchedWithItsCredentialsOnlyOnItsOwnHost() = runBlocking {
        val http = FakeHttp().apply {
            on("lib.example.org/opds/search", fixture("opds_search.xml"))
            on("lib.example.org/opds/osd.xml", fixture("opds_osd.xml"))
            on("lib.example.org/opds/", fixture("opds_root.xml"))
        }
        val settings = newSettings(http)
        val added = settings.addCatalog("https://lib.example.org/opds/", "sam", "secret").getOrThrow()
        http.requests.clear()
        val engine = BookSearchEngine(settings.apply {
            SourceKind.values().filter { it != SourceKind.OPDS }.forEach { setEnabled(it, false) }
        }, http, 5_000)
        val res = engine.search("frankenstein")
        assertEquals(listOf("Frankenstein; Or, The Modern Prometheus", "Plain PDF Book"), res.results.map { it.title })
        assertTrue(res.results[0].id.startsWith("opds:${added.id}:"))
        assertEquals("Example Library", res.results[0].sourceName)
        assertTrue(http.requests.all { it.second == BasicAuth("sam", "secret") })
        assertNull(settings.credentialsFor(added.id, "https://evil.example.net/x.epub"))
        assertNotNull(settings.credentialsFor(added.id, "https://lib.example.org/files/84.epub"))
        // Second search reuses the discovered template: one request only.
        http.requests.clear()
        engine.search("gatsby")
        assertEquals(1, http.requests.size)
    }
}
