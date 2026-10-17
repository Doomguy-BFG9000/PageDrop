package app.pagedrop.converter

import android.content.Context
import java.io.File

/**
 * Unified entry point for converting any supported ebook format to MOBI.
 *
 * Auto-detects the input format from the file extension and delegates
 * to the appropriate converter:
 * - EPUB → [EpubToMobiConverter]
 * - PDF  → [PdfToMobiConverter]
 * - TXT  → [TxtToMobiConverter]
 */
object BookConverter {

    private val CONVERTIBLE_FORMATS = listOf("EPUB", "PDF", "TXT")

    /**
     * Converts the given input file to MOBI format.
     *
     * @param context Android context (needed for PDF conversion)
     * @param inputFile source file (EPUB, PDF, or TXT)
     * @param outputFile target MOBI file (will be created)
     * @param title chosen title; when null the format's own metadata (EPUB OPF, PDF Info) or the output file name is used
     * @param author chosen author; when null the format's own metadata or "Unknown Author" is used
     * @param coverImage chosen cover (JPEG/PNG bytes); when null the EPUB's own cover (if any) is used
     * @return [ConversionResult] with success status, the cover that was embedded, and Kindle UUID
     */
    suspend fun convertToMobi(
        context: Context,
        inputFile: File,
        outputFile: File,
        title: String? = null,
        author: String? = null,
        coverImage: ByteArray? = null,
    ): ConversionResult {
        val fallbackTitle = outputFile.nameWithoutExtension
        val chosenTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val chosenAuthor = author?.trim()?.takeIf { it.isNotEmpty() }
        val chosenCover = coverImage?.takeIf { it.isNotEmpty() }
        return when (inputFile.extension.lowercase()) {
            "epub" -> EpubToMobiConverter.convert(
                inputFile, outputFile, fallbackTitle = fallbackTitle,
                titleOverride = chosenTitle, authorOverride = chosenAuthor, coverOverride = chosenCover,
            )
            "pdf" -> PdfToMobiConverter.convert(
                context, inputFile, outputFile,
                titleOverride = chosenTitle, authorOverride = chosenAuthor, coverOverride = chosenCover,
            )
            "txt" -> TxtToMobiConverter.convert(
                inputFile, outputFile, title = chosenTitle ?: fallbackTitle,
                author = chosenAuthor, coverImage = chosenCover,
            )
            else -> ConversionResult(success = false)
        }
    }

    /**
     * Checks whether the given format string can be converted to MOBI.
     *
     * @param format the file format (e.g. "EPUB", "pdf", "TXT")
     * @return true if we have a converter for this format
     */
    fun canConvert(format: String): Boolean = format.uppercase() in CONVERTIBLE_FORMATS
}
