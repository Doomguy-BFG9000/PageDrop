package app.pagedrop.converter

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.domain.Resource
import nl.siegmann.epublib.epub.EpubReader
import java.io.File
import java.io.FileInputStream

/**
 * Converts EPUB files to MOBI format suitable for Kindle Paperwhite 1st Gen.
 *
 * Pipeline:
 *   1. Parse EPUB with epublib (metadata, spine, cover)
 *   2. Concatenate all spine XHTML into a single HTML document
 *   3. Strip CSS/style tags (Kindle MOBI handles basic HTML only)
 *   4. Extract cover image if present
 *   5. Delegate binary MOBI writing to [MobiWriter]
 */
object EpubToMobiConverter {

    private const val TAG = "EpubToMobiConverter"

    /**
     * Converts an EPUB file to MOBI format.
     *
     * @param epubFile source EPUB file
     * @param mobiFile target MOBI file (will be created)
     * @return [ConversionResult] with success status, cover bytes, and Kindle UUID
     */
    suspend fun convert(
        epubFile: File,
        mobiFile: File,
        fallbackTitle: String? = null,
        titleOverride: String? = null,
        authorOverride: String? = null,
        coverOverride: ByteArray? = null,
    ): ConversionResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting conversion: ${epubFile.name} → ${mobiFile.name}")

            // 1. Parse EPUB
            val epub = FileInputStream(epubFile).use { inputStream ->
                EpubReader().readEpub(inputStream)
            }

            // 2. Extract metadata
            val metadata = epub.metadata
            val title = titleOverride?.takeIf { it.isNotBlank() }
                ?: metadata.firstTitle?.takeIf { it.isNotBlank() }
                ?: fallbackTitle
                ?: mobiFile.nameWithoutExtension
            val author = authorOverride?.takeIf { it.isNotBlank() } ?: metadata.authors.firstOrNull()?.let { a ->
                buildString {
                    if (a.firstname?.isNotBlank() == true) append(a.firstname)
                    if (a.lastname?.isNotBlank() == true) {
                        if (isNotEmpty()) append(" ")
                        append(a.lastname)
                    }
                }
            }?.takeIf { it.isNotBlank() } ?: "Unknown Author"

            Log.d(TAG, "Parsed: \"$title\" by $author, ${epub.spine.spineReferences.size} spine items")

            // 3. Build single HTML document from spine
            val htmlContent = buildHtmlDocument(title, epub)

            // 4. Extract cover image
            val coverImage = coverOverride ?: extractCoverImage(epub)

            // 5. Write MOBI
            val writer = MobiWriter(
                title = title,
                author = author,
                htmlContent = htmlContent.toByteArray(Charsets.UTF_8),
                coverImage = coverImage
            )
            writer.write(mobiFile)

            Log.d(TAG, "Conversion complete: ${mobiFile.length()} bytes")
            ConversionResult(
                success = true,
                coverBytes = coverImage,
                kindleUuid = writer.kindleUuid
            )
        } catch (e: Exception) {
            Log.e(TAG, "Conversion failed: ${e.message}", e)
            if (mobiFile.exists()) mobiFile.delete()
            ConversionResult(success = false)
        }
    }

    /**
     * Builds a single HTML document from all spine items in the EPUB.
     * Strips CSS `<style>` and `<link>` tags since Kindle MOBI only handles basic HTML.
     */
    private fun buildHtmlDocument(title: String, epub: nl.siegmann.epublib.domain.Book): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("<html><head><title>")
        sb.append(escapeHtml(title))
        sb.append("</title></head><body>\n")

        val spineRefs = epub.spine.spineReferences
        for (spineRef in spineRefs) {
            val resource: Resource = spineRef.resource ?: continue
            try {
                val content = String(resource.data, Charsets.UTF_8)
                val bodyContent = extractBodyContent(content)
                sb.append(bodyContent)
                sb.append("\n<mbp:pagebreak/>\n")
            } catch (e: Exception) {
                Log.w(TAG, "Skipping spine item ${resource.href}: ${e.message}")
            }
        }

        sb.append("</body></html>")
        return sb.toString()
    }

    /**
     * Extracts content between `<body>` and `</body>` tags.
     * Falls back to the full content if no body tags are found.
     * Also strips `<style>`, `<link>`, and `<script>` tags.
     */
    private fun extractBodyContent(xhtml: String): String {
        // Try to find body content
        val bodyContent = run {
            val bodyStart = xhtml.indexOf("<body", ignoreCase = true)
            if (bodyStart == -1) return@run xhtml

            // Find the closing > of the <body...> tag
            val bodyTagEnd = xhtml.indexOf('>', bodyStart)
            if (bodyTagEnd == -1) return@run xhtml

            val bodyEnd = xhtml.indexOf("</body>", bodyTagEnd, ignoreCase = true)
            if (bodyEnd == -1) {
                xhtml.substring(bodyTagEnd + 1)
            } else {
                xhtml.substring(bodyTagEnd + 1, bodyEnd)
            }
        }

        // Strip <style>...</style> tags
        val noStyle = STYLE_REGEX.replace(bodyContent, "")
        // Strip <link ...> tags (usually CSS references)
        val noLink = LINK_REGEX.replace(noStyle, "")
        // Strip <script>...</script> tags
        val noScript = SCRIPT_REGEX.replace(noLink, "")
        // Strip <header>...</header> tags (often contain repeated title/author)
        val noHeader = HEADER_REGEX.replace(noScript, "")
        // Strip <nav>...</nav> tags (table of contents, navigation)
        val noNav = NAV_REGEX.replace(noHeader, "")
        // Strip <footer>...</footer> tags
        val noFooter = FOOTER_REGEX.replace(noNav, "")

        return noFooter.trim()
    }

    /**
     * Extracts the cover image bytes from the EPUB, if present.
     */
    private fun extractCoverImage(epub: nl.siegmann.epublib.domain.Book): ByteArray? {
        try {
            // Method 1: Use epublib's cover image
            epub.coverImage?.let { cover ->
                val data = cover.data
                if (data != null && data.isNotEmpty()) {
                    Log.d(TAG, "Found cover image: ${cover.href} (${data.size} bytes)")
                    return data
                }
            }

            // Method 1 covers the main case; no reliable guide API fallback
        } catch (e: Exception) {
            Log.w(TAG, "Could not extract cover image: ${e.message}")
        }
        return null
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    // Regex patterns for stripping unwanted tags (compiled once)
    private val STYLE_REGEX = Regex(
        "<style[^>]*>.*?</style>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val LINK_REGEX = Regex(
        "<link[^>]*(?:/>|>)",
        RegexOption.IGNORE_CASE
    )
    private val SCRIPT_REGEX = Regex(
        "<script[^>]*>.*?</script>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val HEADER_REGEX = Regex(
        "<header[^>]*>.*?</header>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val NAV_REGEX = Regex(
        "<nav[^>]*>.*?</nav>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val FOOTER_REGEX = Regex(
        "<footer[^>]*>.*?</footer>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
}
