package app.pagedrop.metadata

import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File

/** Writes Title / Author into a PDF's Info dictionary (stale XMP metadata is dropped so viewers agree). */
object PdfMetadataWriter {
    private const val MAX_BYTES = 80L * 1024 * 1024

    data class Info(val title: String?, val author: String?)

    fun read(file: File): Info = PDDocument.load(file).use { doc ->
        Info(doc.documentInformation.title, doc.documentInformation.author)
    }

    /** Writes a copy of [src] to [dst]. Throws on encrypted/corrupt/huge files; the caller falls back to the original. */
    fun write(src: File, dst: File, title: String?, author: String?) {
        require(src.length() <= MAX_BYTES) { "PDF too large to edit in memory" }
        PDDocument.load(src).use { doc ->
            check(!doc.isEncrypted) { "Encrypted PDF" }
            title?.takeIf { it.isNotBlank() }?.let { doc.documentInformation.title = it }
            author?.takeIf { it.isNotBlank() }?.let { doc.documentInformation.author = it }
            doc.documentCatalog.metadata = null
            doc.save(dst)
        }
        val check = read(dst)
        check(title.isNullOrBlank() || check.title == title) { "PDF title did not round-trip" }
        check(author.isNullOrBlank() || check.author == author) { "PDF author did not round-trip" }
    }
}
