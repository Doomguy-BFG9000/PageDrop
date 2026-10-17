package app.pagedrop.converter

import app.pagedrop.metadata.MobiMetadataRewriter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ConverterMetadataTest {
    @get:Rule val tmp = TemporaryFolder()

    private val jpeg = ByteArray(3000) { if (it < 3) listOf(0xFF, 0xD8, 0xFF)[it].toByte() else (it % 251).toByte() }

    @Test fun mobiWriterWritesCoverOffsetAndMetadata() {
        val out = tmp.newFile()
        MobiWriter("Dune", "Frank Herbert", "<html><body><p>x</p></body></html>".toByteArray(), coverImage = jpeg).write(out)
        val info = MobiMetadataRewriter.read(out)
        assertEquals("Dune", info.title)
        assertEquals("Frank Herbert", info.author)
        assertNotNull("EXTH 201 must point at the cover record", info.coverRecord)
        assertEquals(1 + 1, info.coverRecord) // record 0, one text record, then the cover at index 2
    }

    @Test fun mobiWriterWithoutCoverHasNoCoverOffset() {
        val out = tmp.newFile()
        MobiWriter("Dune", "Frank Herbert", "<html/>".toByteArray()).write(out)
        assertNull(MobiMetadataRewriter.read(out).coverRecord)
    }

    @Test fun txtConversionCarriesAuthorAndCover() {
        val txt = tmp.newFile("note.txt").apply { writeText("Hello\n\nWorld") }
        val out = File(tmp.root, "out.mobi")
        val r = runBlocking { TxtToMobiConverter.convert(txt, out, title = "Hello Book", author = "Jane Doe", coverImage = jpeg) }
        assertEquals(true, r.success)
        val info = MobiMetadataRewriter.read(out)
        assertEquals("Hello Book", info.title); assertEquals("Jane Doe", info.author); assertNotNull(info.coverRecord)
        assertEquals(jpeg.toList(), r.coverBytes!!.toList())
    }

    @Test fun txtConversionDefaultsStayUnknownAuthor() {
        val txt = tmp.newFile("note2.txt").apply { writeText("Hello") }
        val out = File(tmp.root, "out2.mobi")
        runBlocking { TxtToMobiConverter.convert(txt, out) }
        assertEquals("Unknown Author", MobiMetadataRewriter.read(out).author)
    }
}
