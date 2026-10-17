package app.pagedrop.discover

import app.pagedrop.metadata.BookFileMetadataEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Hits the real sites. Skipped unless PAGEDROP_LIVE=1:  PAGEDROP_LIVE=1 ./gradlew testDebugUnitTest --tests '*LiveSmokeTest' */
class LiveSmokeTest {
    private val live get() = System.getenv("PAGEDROP_LIVE") == "1"

    @Test
    fun frankensteinAcrossAllSourcesAndRealDownloads() = runBlocking {
        assumeTrue(live)
        val http = OkHttpDiscoverHttp()
        val engine = BookSearchEngine(newSettings(http), http)
        val res = engine.search("frankenstein")
        println("LIVE errors=${res.errors}")
        res.results.take(12).forEach { println("LIVE ${it.source} | ${it.title} | ${it.author} | best=${engine.bestOption(it)?.format} | ${it.options.map { o -> o.format }}") }
        val se = res.results.first { it.source == SourceKind.STANDARD_EBOOKS }
        assertEquals(BookFormat.AZW3, engine.bestOption(se)!!.format)
        assertTrue(res.results.any { it.source == SourceKind.STANDARD_EBOOKS }) // Gutenberg duplicates fold into this edition.

        val dl = BookDownloader(tempDir(), http) { _, _ -> null }
        val seFile = dl.download(se, engine.bestOption(se)!!) { }
        println("LIVE SE download ${seFile.name} ${seFile.length()} bytes")

        // Gutenberg alone (SE would absorb it as a duplicate).
        val gutOnly = newSettings(http).apply { setEnabled(SourceKind.STANDARD_EBOOKS, false); setEnabled(SourceKind.INTERNET_ARCHIVE, false) }
        val gutEngine = BookSearchEngine(gutOnly, http)
        val gut = gutEngine.resolve(gutEngine.search("frankenstein").results.first { it.id == "gutenberg:84" })
        val mobiOpt = gut.options.first { it.format == BookFormat.MOBI }
        val progress = mutableListOf<Float?>()
        val file: File = dl.download(gut, mobiOpt) { progress += it }
        println("LIVE Gutenberg MOBI ${file.name} ${file.length()} bytes, progress steps=${progress.size}")
        assertTrue(file.length() > 100_000)
        val out = BookFileMetadataEmbedder.embed(File(file.path).copyTo(File(file.parentFile, "copy.mobi"), true), "MOBI", "Frankenstein", "Mary Shelley", null)
        println("LIVE embed into Gutenberg MOBI -> $out")
        val kf8 = dl.download(gut, gut.options.first { it.format == BookFormat.AZW3 }) { }
        println("LIVE Gutenberg AZW3 ${kf8.name} ${kf8.length()}")

        val ia = res.results.firstOrNull { it.needsResolve }
        if (ia != null) {
            val resolved = engine.resolve(ia)
            println("LIVE IA resolve ${ia.id} -> ${resolved.options.map { "${it.format}:${it.sizeBytes}" }}")
        }
        for (q in listOf("great gatsby", "pride and prejudice")) {
            val r = engine.search(q)
            println("LIVE '$q' errors=${r.errors} top=${r.results.take(4).map { "${it.source}:${it.title}" }}")
            assertNotNull(r.results.firstOrNull())
        }
    }

    @Test
    fun englishSourcesAndRealEpubDownloads() = runBlocking {
        assumeTrue(live)
        val http = OkHttpDiscoverHttp()
        val settings = newSettings(http, allSources = true).apply {
            setOf(SourceKind.DIGITAL_LIBRARY_INDIA, SourceKind.HINDWI, SourceKind.REKHTA, SourceKind.HINDI_WIKISOURCE)
                .forEach { setEnabled(it, false) }
        }
        val engine = BookSearchEngine(settings, http)
        for (query in listOf("frankenstein", "gatsby", "pride and prejudice")) {
            val results = engine.search(query)
            println("LIVE English '$query': ${results.results.size} results; sources=${results.results.map { it.source }.toSet()}; errors=${results.errors}")
            assertTrue(results.results.isNotEmpty())
        }
        val faded = FadedPageSource(http)
        val book = faded.search("gatsby").first { it.id == "faded_page:20181181" }
        val resolved = engine.resolve(book)
        val many = ManyBooksSource(http).search("frankenstein")
        assertTrue(many.any { it.title == "Frankenstein" })
        assertTrue(WikisourceSource(http, SourceKind.ENGLISH_WIKISOURCE).search("frankenstein").isNotEmpty())
        val gut = engine.resolve(GutenbergSource(http).search("frankenstein").first { it.id == "gutenberg:84" })
        val dir = tempDir()
        try {
            val downloader = BookDownloader(dir, http) { _, _ -> null }
            for (r in listOf(resolved, gut)) {
                val file = downloader.download(r, r.options.first { it.format == BookFormat.EPUB }) { }
                println("LIVE English EPUB validated: ${r.sourceName} ${file.length()} bytes")
                assertTrue(file.length() > 100_000)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test
    fun indianSourcesAndRealArchiveEpub() = runBlocking {
        assumeTrue(live)
        val http = OkHttpDiscoverHttp()
        val sources = listOf(
            RekhtaSource(http, SourceKind.HINDWI) to "गोदान",
            RekhtaSource(http, SourceKind.REKHTA) to "raag darbari",
            WikisourceSource(http) to "गोदान",
            OpenLibrarySource(http) to "frankenstein",
            InternetArchiveSource(http, SourceKind.DIGITAL_LIBRARY_INDIA) to "gunahon",
        )
        for ((source, query) in sources) {
            val books = source.search(query)
            println("LIVE ${source.name}: ${books.size} results; first=${books.firstOrNull()?.title}")
            assertTrue("${source.name} returned no results", books.isNotEmpty())
            assertTrue(books.all { it.source == source.kind && it.websiteUrl != null })
        }
        val combined = BookSearchEngine(newSettings(http, allSources = true), http).search("गोदान")
        println("LIVE combined Hindi search: ${combined.results.size} results; errors=${combined.errors}")
        assertTrue(combined.results.any { it.source == SourceKind.HINDWI || it.source == SourceKind.HINDI_WIKISOURCE })
        val result = BookResult(
            "digital_library_india:in.ernet.dli.2015.348947", SourceKind.DIGITAL_LIBRARY_INDIA,
            "Digital Library of India", "गुनाहों का देवता", "धर्मवीर भारती", options = emptyList(),
            needsResolve = true, archiveIdentifier = "in.ernet.dli.2015.348947",
        )
        val resolved = InternetArchiveSource(http).resolve(result)
        val epub = resolved.options.first { it.format == BookFormat.EPUB }
        val dir = tempDir()
        try {
            val file = BookDownloader(dir, http) { _, _ -> null }.download(resolved, epub) { }
            println("LIVE Hindi EPUB validated: ${file.length()} bytes")
            assertTrue(file.length() > 100_000)
        } finally { dir.deleteRecursively() }
    }
}
