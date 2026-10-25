package app.pagedrop.metadata

import java.io.File
import java.util.zip.ZipFile

/** Rejects metadata values that are junk (file names, placeholders, tool defaults, site spam, product codes). */
object MetadataJunk {
    private val placeholder = Regex(
        "^(?:unknown|untitled|none|null|\\(null\\)|n/?a|title|no title|document|new document|book|default|test|temp|noname|no name)\\b.*$|" +
            "^(?:microsoft\\s+(?:word|powerpoint|excel)\\b|adobe\\b|quarkxpress|pdfcreator|acrobat|scribus|libreoffice|openoffice|calibre|nitro|foxit|abbyy|pdf24|cutepdf|dopdf|primopdf)\\b.*$|" +
            "^untitled.*$",
        RegexOption.IGNORE_CASE,
    )
    private val fileExt = Regex("\\.(?:docx?|rtf|indd|qxd|tmp|pdf|epub|mobi|azw3?|txt|odt|pages|fb2|djvu?)$", RegexOption.IGNORE_CASE)
    private val spam = Regex("www\\.|https?:|\\.(?:com|org|net|sk|is|io|ru)\\b|z-?lib|libgen|oceanofpdf|pdfdrive|download|ebook|e-book|1lib", RegexOption.IGNORE_CASE)
    private val codeLike = Regex("^[A-Z0-9][A-Z0-9_-]{5,}$")
    private val badAuthors = setOf(
        "admin", "administrator", "user", "owner", "pc", "windows user", "calibre", "ebook", "ebooks", "author", "unknown",
        "unknown author", "anonymous", "publisher", "scanner", "scan", "root", "default", "hp", "dell", "kindle", "n/a", "na", "none", "-",
    )

    fun isUsableTitle(raw: String?): Boolean {
        val t = raw?.trim().orEmpty()
        if (t.length < 2 || t.length > 200) return false
        if (t.none { it.isLetterOrDigit() }) return false
        if (placeholder.matches(t) || fileExt.containsMatchIn(t) || spam.containsMatchIn(t)) return false
        if (codeLike.matches(t) && t.any { it.isDigit() }) return false
        if (BookNameCleaner.looksLikeFilename(t)) return false
        return true
    }

    fun isUsableAuthor(raw: String?): Boolean {
        val a = raw?.trim().orEmpty()
        if (a.length < 2 || a.length > 120) return false
        if (a.lowercase() in badAuthors || BookNameCleaner.isPlaceholderAuthor(a)) return false
        if (spam.containsMatchIn(a) || placeholder.matches(a) || fileExt.containsMatchIn(a)) return false
        if (a.none { it.isLetter() } || a.any { it.isDigit() } || '_' in a) return false
        return true
    }
}

/** Title / author (and cover when cheap) already stored inside a book file. Never throws. */
object EmbeddedMetadataReader {
    data class Embedded(val title: String?, val author: String?, val cover: ByteArray? = null) {
        val isEmpty: Boolean get() = title == null && author == null
        companion object { val NONE = Embedded(null, null) }
    }

    private const val MAX_COVER = 6 * 1024 * 1024

    fun read(file: File, format: String): Embedded {
        if (!file.isFile) return Embedded.NONE
        val f = format.uppercase().ifBlank { file.extension.uppercase() }
        return try {
            val raw = when (f) {
                "PDF" -> readPdf(file)
                "EPUB" -> readEpub(file)
                "MOBI", "AZW3", "AZW", "PRC", "KFX" -> readMobi(file)
                else -> Embedded.NONE
            }
            sanitize(raw)
        } catch (e: Exception) {
            Embedded.NONE
        } catch (e: LinkageError) {
            Embedded.NONE
        } catch (e: OutOfMemoryError) {
            Embedded.NONE
        }
    }

    private fun sanitize(e: Embedded): Embedded {
        val title = e.title?.trim()?.takeIf { MetadataJunk.isUsableTitle(it) }
        val author = flipLastFirst(e.author?.trim())?.takeIf { MetadataJunk.isUsableAuthor(it) }
        return Embedded(title, author, e.cover?.takeIf { CoverImages.isUsableCover(it) })
    }

    /** "Fitzgerald, F. Scott" -> "F. Scott Fitzgerald" (only for a single comma and a short name). */
    internal fun flipLastFirst(a: String?): String? {
        if (a == null) return null
        val parts = a.split(',').map { it.trim() }
        if (parts.size == 2 && parts.all { it.isNotEmpty() } && a.split(' ').size <= 5 && parts[0].split(' ').size <= 2) {
            return "${parts[1]} ${parts[0]}"
        }
        return a
    }

    private fun readPdf(file: File): Embedded {
        val info = PdfMetadataWriter.read(file)
        return Embedded(info.title, info.author)
    }

    private fun readMobi(file: File): Embedded {
        val info = MobiMetadataRewriter.read(file)
        val cover = info.coverRecord?.let { runCatching { MobiMetadataRewriter.readCover(file) }.getOrNull() }
        return Embedded(info.title ?: info.fullName, info.author, cover)
    }

    // ── EPUB: zip + OPF, no full-book parse ──────────────────────────────────

    private fun readEpub(file: File): Embedded = ZipFile(file).use { zip ->
        fun text(name: String): String? = zip.getEntry(name)?.takeIf { it.size in 0..(4L shl 20) }
            ?.let { e -> zip.getInputStream(e).use { String(it.readBytes(), Charsets.UTF_8) } }

        val container = text("META-INF/container.xml") ?: return@use Embedded.NONE
        val opfPath = Regex("full-path\\s*=\\s*[\"']([^\"']+)[\"']").find(container)?.groupValues?.get(1) ?: return@use Embedded.NONE
        val opf = text(opfPath) ?: return@use Embedded.NONE
        val title = dc(opf, "title")
        val author = dc(opf, "creator")

        val base = opfPath.substringBeforeLast('/', "")
        fun resolve(href: String): String {
            val decoded = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
            return if (base.isEmpty()) decoded else normalize("$base/$decoded")
        }
        val items = Regex("<(?:\\w+:)?item\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(opf).map { it.value }.toList()
        fun attr(tag: String, name: String) = Regex("\\b$name\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        val coverId = Regex("<meta\\b[^>]*name\\s*=\\s*[\"']cover[\"'][^>]*>", RegexOption.IGNORE_CASE).find(opf)?.value?.let { attr(it, "content") }
        val coverItem = items.firstOrNull { attr(it, "properties")?.split(' ')?.contains("cover-image") == true }
            ?: coverId?.let { id -> items.firstOrNull { attr(it, "id") == id } }
        val cover = coverItem?.let { attr(it, "href") }?.let { href ->
            zip.getEntry(resolve(href))?.takeIf { it.size in 1..MAX_COVER }?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        }
        Embedded(title, author, cover)
    }

    private fun normalize(path: String): String {
        val out = ArrayDeque<String>()
        for (p in path.split('/')) when (p) { "", "." -> {}; ".." -> out.removeLastOrNull(); else -> out.addLast(p) }
        return out.joinToString("/")
    }

    private fun dc(opf: String, name: String): String? {
        val m = Regex("<(?:dc:)?$name\\b[^>]*>(.*?)</(?:dc:)?$name>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(opf)
            ?: return null
        return unescape(m.groupValues[1].replace(Regex("<[^>]+>"), "")).replace(Regex("\\s+"), " ").trim().ifEmpty { null }
    }

    private fun unescape(s: String): String = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
}
