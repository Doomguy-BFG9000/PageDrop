package app.pagedrop.converter

import android.content.Context
import android.util.Log
import app.pagedrop.metadata.BookNameCleaner
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts PDF files to MOBI format suitable for Kindle.
 *
 * Pipeline:
 *   1. Extract text from each PDF page using PDFBox
 *   2. Reflow the text ([PdfTextReflow]): strip running headers/page numbers,
 *      unwrap hard-wrapped lines into paragraphs, detect chapter headings, and
 *      emit simple HTML (`<p>` per paragraph, `<h2>` + page break per heading)
 *   3. Delegate binary MOBI writing to [MobiWriter]
 *
 * Note: Layout, images, and complex formatting are NOT preserved —
 * only the text content is extracted.
 */
object PdfToMobiConverter {

    private const val TAG = "PdfToMobiConverter"

    /**
     * Converts a PDF file to MOBI format.
     *
     * @param context Android context (needed for PDFBox resource initialization)
     * @param pdfFile source PDF file
     * @param mobiFile target MOBI file (will be created)
     * @return [ConversionResult] with success status and Kindle UUID (PDFs have no cover)
     */
    suspend fun convert(
        context: Context,
        pdfFile: File,
        mobiFile: File,
        titleOverride: String? = null,
        authorOverride: String? = null,
        coverOverride: ByteArray? = null,
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting conversion: ${pdfFile.name} → ${mobiFile.name}")

            // Ensure PDFBox resources are initialized
            if (!PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(context.applicationContext)
            }

            // 1. Load PDF document
            val document = PDDocument.load(pdfFile)
            document.use { doc ->
                val numberOfPages = doc.numberOfPages
                Log.d(TAG, "PDF has $numberOfPages pages")

                // 2. Title/author: chosen metadata, else the PDF's own Info dictionary (when it
                //    looks real), else the output file name (never the temp input file name).
                val info = runCatching { doc.documentInformation }.getOrNull()
                val pdfTitle = info?.title?.trim()?.takeIf { it.isNotEmpty() && !BookNameCleaner.looksLikeFilename(it) }
                val pdfAuthor = info?.author?.trim()?.takeIf { !BookNameCleaner.isPlaceholderAuthor(it) }
                val title = titleOverride?.takeIf { it.isNotBlank() }
                    ?: pdfTitle?.takeIf { mobiFile.nameWithoutExtension.let(BookNameCleaner::looksLikeFilename) }
                    ?: mobiFile.nameWithoutExtension
                val author = authorOverride?.takeIf { it.isNotBlank() } ?: pdfAuthor ?: "Unknown Author"

                // 3. Build HTML from extracted text
                val htmlContent = buildHtmlFromPdf(title, doc, numberOfPages)

                // 4. Write MOBI
                val writer = MobiWriter(
                    title = title,
                    author = author,
                    htmlContent = htmlContent.toByteArray(Charsets.UTF_8),
                    coverImage = coverOverride,
                )
                writer.write(mobiFile)

                Log.d(TAG, "Conversion complete: ${mobiFile.length()} bytes")
                ConversionResult(
                    success = true,
                    coverBytes = coverOverride,
                    kindleUuid = writer.kindleUuid
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.message}", e)
            if (mobiFile.exists()) mobiFile.delete()
            ConversionResult(success = false)
        }
    }

    /**
     * Extracts text per PDF page, reflows it into paragraphs/headings via
     * [PdfTextReflow], and renders simple HTML.
     */
    private fun buildHtmlFromPdf(title: String, document: PDDocument, numberOfPages: Int): String {
        val stripper = PDFTextStripper()
        val pages = ArrayList<String>(numberOfPages)

        for (page in 1..numberOfPages) {
            stripper.startPage = page
            stripper.endPage = page
            pages.add(stripper.getText(document) ?: "")
        }

        return PdfTextReflow.buildHtml(title, PdfTextReflow.reflow(pages))
    }
}
