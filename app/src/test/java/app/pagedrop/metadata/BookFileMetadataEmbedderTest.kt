package app.pagedrop.metadata

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BookFileMetadataEmbedderTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pdf(title: String? = null): File {
        val f = tmp.newFile("book.pdf")
        PDDocument().use { doc ->
            doc.addPage(PDPage())
            doc.addPage(PDPage())
            if (title != null) doc.documentInformation.title = title
            doc.save(f)
        }
        return f
    }

    @Test fun pdfInfoDictionaryIsUpdatedInPlace() {
        val f = pdf("OD4388757722")
        val outcome = BookFileMetadataEmbedder.embed(f, "PDF", "Tender Is the Flesh", "Agustina Bazterrica", null)
        assertEquals(outcome.detail, BookFileMetadataEmbedder.Status.EMBEDDED, outcome.status)
        val info = PdfMetadataWriter.read(f)
        assertEquals("Tender Is the Flesh", info.title)
        assertEquals("Agustina Bazterrica", info.author)
        PDDocument.load(f).use { assertEquals(2, it.numberOfPages) }
        assertTrue(!File(f.parentFile, ".book.pdf.meta.tmp").exists())
    }

    @Test fun placeholderAuthorIsNotWrittenToPdf() {
        val f = pdf()
        BookFileMetadataEmbedder.embed(f, "PDF", "Title", "Unknown Author", null)
        assertNull(PdfMetadataWriter.read(f).author)
    }

    @Test fun epubAndTxtAreUnsupportedNotFailed() {
        val f = tmp.newFile("a.epub")
        assertEquals(BookFileMetadataEmbedder.Status.UNSUPPORTED, BookFileMetadataEmbedder.embed(f, "EPUB", "T", "A", null).status)
    }

    @Test fun corruptPdfKeepsOriginal() {
        val f = tmp.newFile("bad.pdf").apply { writeText("not a pdf") }
        val outcome = BookFileMetadataEmbedder.embed(f, "PDF", "T", "A", null)
        assertEquals(BookFileMetadataEmbedder.Status.FAILED, outcome.status)
        assertEquals("not a pdf", f.readText())
    }
}
