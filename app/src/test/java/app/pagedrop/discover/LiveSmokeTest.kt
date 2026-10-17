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
        assertTrue(res.results.any { it.source == SourceKind.GUTENBERG })

        val dl = BookDownloader(tempDir(), http) { _, _ -> null }
        val seFile = dl.download(se, engine.bestOption(se)!!) { }
        println("LIVE SE download ${seFile.name} ${seFile.length()} bytes")

        // Gutenberg alone (SE would absorb it as a duplicate).
        val gutOnly = newSettings(http).apply { setEnabled(SourceKind.STANDARD_EBOOKS, false); setEnabled(SourceKind.INTERNET_ARCHIVE, false) }
        val gut = BookSearchEngine(gutOnly, http).search("frankenstein").results.first { it.id == "gutenberg:84" }
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
}
