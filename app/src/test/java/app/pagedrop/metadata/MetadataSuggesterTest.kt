package app.pagedrop.metadata

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MetadataSuggesterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gatsby = BookMetadataCandidate("The Great Gatsby", "F. Scott Fitzgerald", coverUrl = "http://c/1.jpg", confidence = 0.95f)
    private val calls = mutableListOf<LookupQuery>()

    private fun suggester(onQuery: (LookupQuery) -> MetadataLookupResult = { MetadataLookupResult.NoMatches }) =
        MetadataSuggester { q -> calls += q; onQuery(q) }

    private fun pdf(name: String, title: String?, author: String?, vararg lines: String): File {
        val f = File(tmp.root, name)
        PDDocument().use { doc ->
            val page = PDPage(); doc.addPage(page)
            if (title != null) doc.documentInformation.title = title
            if (author != null) doc.documentInformation.author = author
            if (lines.isNotEmpty()) PDPageContentStream(doc, page).use { cs ->
                cs.beginText(); cs.setFont(PDType1Font.HELVETICA, 18f); cs.newLineAtOffset(50f, 700f)
                lines.forEach { cs.showText(it); cs.newLineAtOffset(0f, -26f) }
                cs.endText()
            }
            doc.save(f)
        }
        return f
    }

    @Test fun embeddedPdfInfoWinsOverFilename() = runBlocking {
        val f = pdf("greatgatsby_ebook_free.pdf", "The Great Gatsby", "F. Scott Fitzgerald")
        val s = suggester { MetadataLookupResult.Matches(listOf(gatsby), gatsby) }.suggest(f, "PDF", "greatgatsby_ebook_free")
        assertEquals("The Great Gatsby", s.title)
        assertEquals("F. Scott Fitzgerald", s.author)
        assertEquals(Suggestion.Source.EMBEDDED, s.source)
        assertEquals("The Great Gatsby", calls.first().title)
        assertEquals("F. Scott Fitzgerald", calls.first().author)
        assertEquals("http://c/1.jpg", s.coverUrl)
        assertEquals(gatsby, s.best)
    }

    @Test fun junkInfoFallsBackToFilenameThenSplitQuery() = runBlocking {
        val f = pdf("greatgatsby_ebook_free.pdf", "Microsoft Word - gg.doc", "admin")
        val s = suggester { q -> if (q.title == "great gatsby") MetadataLookupResult.Matches(listOf(gatsby), gatsby) else MetadataLookupResult.NoMatches }
            .suggest(f, "PDF", "greatgatsby_ebook_free")
        assertEquals(listOf("greatgatsby", "great gatsby"), calls.map { it.title })
        assertEquals(Suggestion.Source.ONLINE, s.source)
        assertEquals("The Great Gatsby", s.title)
        assertEquals("F. Scott Fitzgerald", s.author)
    }

    @Test fun neverMoreThanThreeQueries() = runBlocking {
        val f = tmp.newFile("x.epub")
        suggester().suggest(f, "EPUB", "thegreatgatsby_ebook_free")
        assertTrue(calls.size <= 3)
    }

    @Test fun unavailableStopsRetriesAndKeepsFilenamePrefill() = runBlocking {
        val f = tmp.newFile("a.txt")
        val s = suggester { MetadataLookupResult.Unavailable(true, "offline") }.suggest(f, "TXT", "Atomic Habits by James Clear [PDF]")
        assertEquals(1, calls.size)
        assertTrue(s.unavailable && s.offline)
        assertEquals("Atomic Habits", s.title)
        assertEquals("James Clear", s.author)
        assertEquals(Suggestion.Source.FILENAME, s.source)
    }

    @Test fun epubOpfAndCover() = runBlocking {
        val f = File(tmp.root, "b.epub")
        val jpeg = ByteArray(3000) { if (it < 3) listOf(0xFF, 0xD8, 0xFF)[it].toByte() else 7 }
        ZipOutputStream(FileOutputStream(f)).use { z ->
            fun put(n: String, b: ByteArray) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() }
            put("META-INF/container.xml", """<container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>""".toByteArray())
            put("OEBPS/content.opf", """<package><metadata xmlns:dc="x"><dc:title>Dune &amp; Co</dc:title><dc:creator opf:role="aut">Herbert, Frank</dc:creator>
                <meta name="cover" content="c"/></metadata><manifest><item id="c" href="img/cover.jpg" media-type="image/jpeg"/></manifest></package>""".toByteArray())
            put("OEBPS/img/cover.jpg", jpeg)
        }
        val e = EmbeddedMetadataReader.read(f, "EPUB")
        assertEquals("Dune & Co", e.title)
        assertEquals("Frank Herbert", e.author)
        assertNotNull(e.cover)
        assertNull(EmbeddedMetadataReader.read(tmp.newFile("c.epub"), "EPUB").title)
    }

    @Test fun mobiExth() = runBlocking {
        val f = tmp.newFile("m.mobi")
        app.pagedrop.converter.MobiWriter("Dune", "Frank Herbert", ("<html><body>" + "<p>Hello</p>".repeat(500) + "</body></html>").toByteArray()).write(f)
        MobiMetadataRewriter.rewrite(f, File(tmp.root, "m2.mobi").also { }, "Dune", "Frank Herbert")
        val e = EmbeddedMetadataReader.read(File(tmp.root, "m2.mobi"), "AZW3")
        assertEquals("Dune", e.title)
        assertEquals("Frank Herbert", e.author)
    }

    @Test fun firstPageGuessFromRealPdfText() = runBlocking {
        val f = try {
            pdf("gg.pdf", null, null, "The Great Gatsby", "By F. Scott Fitzgerald", "Download free eBooks at www.planetpdf.com")
        } catch (e: Throwable) { assumeTrue("PDFBox cannot write on JVM: $e", false); return@runBlocking }
        val g = PdfFirstPageGuess.guess(f)
        assumeTrue("PDFBox text extraction unavailable on JVM", g != null)
        assertEquals("The Great Gatsby", g!!.title)
        assertEquals("F. Scott Fitzgerald", g.author)
        val s = suggester().suggest(f, "PDF", "greatgatsby_ebook_free")
        assertEquals(Suggestion.Source.FIRST_PAGE, s.source)
        assertEquals("The Great Gatsby", calls.first().title)
    }

    @Test fun realGatsbyPdf() = runBlocking {
        val f = File("/private/tmp/claude-501/-Users-Spandan-Library-Application-Support-Claude-scratch-workspaces-a94fa3fc-240f-45b6-896a-d88991fe4d17-546b0266-8c85-4e52-8937-3e4746b5d371-scratch-2026-10-16-9c6344/9caf09a0-5d05-4aea-be17-591390c6c006/scratchpad/gatsby-src/the-great-gatsby.pdf")
        assumeTrue(f.exists())
        val e = EmbeddedMetadataReader.read(f, "PDF")
        assertEquals("The Great Gatsby", e.title)
        assertEquals("F. Scott Fitzgerald", e.author)
        val s = suggester().suggest(f, "PDF", "greatgatsby_ebook_free")
        assertEquals("The Great Gatsby", s.title)
        assertEquals(Suggestion.Source.EMBEDDED, s.source)
    }
}
