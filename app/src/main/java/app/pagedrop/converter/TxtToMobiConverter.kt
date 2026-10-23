package app.pagedrop.converter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Converts plain text (.txt) files to MOBI format suitable for Kindle.
 *
 * Pipeline:
 *   1. Read the .txt file as UTF-8 text
 *   2. Split into paragraphs by blank lines
 *   3. Wrap each paragraph in `<p>` tags with HTML entity escaping
 *   4. Delegate binary MOBI writing to [MobiWriter]
 */
object TxtToMobiConverter {

    private const val TAG = "TxtToMobiConverter"

    /**
     * Converts a text file to MOBI format.
     *
     * @param txtFile source .txt file
     * @param mobiFile target MOBI file (will be created)
     * @param title optional title override; defaults to the filename without extension
     * @param author optional author; defaults to "Unknown Author"
     * @param coverImage optional cover (JPEG/PNG bytes) embedded in the MOBI
     * @return [ConversionResult] with success status and Kindle UUID (TXT has no cover)
     */
    suspend fun convert(
        txtFile: File,
        mobiFile: File,
        title: String? = null,
        author: String? = null,
        coverImage: ByteArray? = null,
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            val bookTitle = title?.takeIf { it.isNotBlank() } ?: txtFile.nameWithoutExtension
            Log.d(TAG, "Starting conversion: ${txtFile.name} → ${mobiFile.name}")

            // 1. Read text content
            val rawText = txtFile.readText(Charsets.UTF_8)

            if (rawText.isBlank()) {
                Log.w(TAG, "Text file is empty")
                return@withContext ConversionResult(success = false)
            }

            // 2. Build HTML from text
            val htmlContent = buildHtmlFromText(bookTitle, rawText)

            // 3. Write MOBI
            val writer = MobiWriter(
                title = bookTitle,
                author = author?.takeIf { it.isNotBlank() } ?: "Unknown Author",
                htmlContent = htmlContent.toByteArray(Charsets.UTF_8),
                coverImage = coverImage,
            )
            writer.write(mobiFile)

            Log.d(TAG, "Conversion complete: ${mobiFile.length()} bytes")
            ConversionResult(
                success = true,
                coverBytes = coverImage,
                kindleUuid = writer.kindleUuid
            )
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.javaClass.simpleName}")
            if (mobiFile.exists()) mobiFile.delete()
            ConversionResult(success = false)
        }
    }

    /**
     * Builds a simple HTML document from raw text.
     * Paragraphs are split by blank lines (double newlines).
     * Single newlines within a paragraph are preserved as `<br/>`.
     */
    private fun buildHtmlFromText(title: String, text: String): String {
        val sb = StringBuilder(text.length + 1024)
        sb.append("<html><head><title>")
        sb.append(escapeHtml(title))
        sb.append("</title></head><body>\n")

        sb.append("<h1>")
        sb.append(escapeHtml(title))
        sb.append("</h1>\n")

        // Split into paragraphs by blank lines
        val paragraphs = text.split(Regex("\\r?\\n\\s*\\r?\\n"))
        for (paragraph in paragraphs) {
            val trimmed = paragraph.trim()
            if (trimmed.isNotEmpty()) {
                sb.append("<p>")
                sb.append(escapeHtml(trimmed).replace("\n", "<br/>"))
                sb.append("</p>\n")
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
