package app.pagedrop.tools.dashboard

/**
 * Builds and validates the one shell script that deletes books from the Kindle. Everything here is pure
 * (no I/O) so the safety rules can be unit-tested: only paths from the latest listing, inside the two book
 * folders, ending in a book extension, are ever put in a command, and every one is single-quoted.
 */
object KindleDelete {
    const val HOME_DIR = "/mnt/us/documents"
    const val KOREADER_DIR = "/mnt/us/Books"
    const val THUMBNAILS_DIR = "/mnt/us/system/thumbnails"

    private val ROOTS = listOf(HOME_DIR, KOREADER_DIR)
    private val BOOK_EXTENSIONS = setOf("azw", "azw3", "mobi", "prc", "pdf", "epub", "txt")
    private val THUMBNAIL_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    /** Wraps [text] in single quotes for `sh`, escaping embedded single quotes as `'\''`. */
    fun shellQuote(text: String): String = "'" + text.replace("'", "'\\''") + "'"

    private fun hasForbiddenChars(text: String): Boolean = text.any { it.code < 0x20 || it.code == 0x7f }

    /** True when [dir] is exactly a book folder or one subfolder level below it (the listing uses maxdepth 2). */
    internal fun isAllowedParent(dir: String): Boolean {
        if (hasForbiddenChars(dir) || !dir.startsWith("/")) return false
        val parts = dir.split('/')
        if (parts.drop(1).any { it.isEmpty() || it == "." || it == ".." }) return false
        return ROOTS.any { root ->
            dir == root || (dir.startsWith("$root/") && dir.removePrefix("$root/").let { sub ->
                !sub.contains('/') && !sub.endsWith(".sdr", ignoreCase = true)
            })
        }
    }

    /** Whether [path] may be deleted: a book file from the latest listing ([allowed]) in an allowed folder. */
    fun isDeletableBookPath(path: String, allowed: Set<String>): Boolean {
        if (path !in allowed) return false
        if (hasForbiddenChars(path) || !path.startsWith("/")) return false
        if (path.contains("/../") || path.contains("/./") || path.endsWith("/..") || path.endsWith("/.")) return false
        val parent = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        if (!isAllowedParent(parent)) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in BOOK_EXTENSIONS) return false
        return name.substringBeforeLast('.').isNotBlank()
    }

    /** The reading-progress folder KOReader / the stock reader keep next to a book, or null if it would be unsafe. */
    fun sidecarFor(path: String): String? {
        val parent = path.substringBeforeLast('/', "")
        val stem = path.substringAfterLast('/').substringBeforeLast('.')
        if (stem.isBlank() || !isAllowedParent(parent)) return null
        return "$parent/$stem.sdr"
    }

    fun thumbnailPath(id: String): String? =
        id.takeIf { THUMBNAIL_ID.matches(it) }?.let { "$THUMBNAILS_DIR/thumbnail_${it}_EBOK_portrait.jpg" }

    /**
     * The script for one SSH exec. Each book prints `OK|path` or `FAIL|path`; sidecars print `OK|dir` / `FAIL|dir`
     * (only when the folder exists). Thumbnails are removed best effort with no output.
     * @throws IllegalArgumentException if any path is not deletable.
     */
    fun buildScript(
        paths: List<String>,
        deleteSidecars: Boolean,
        allowed: Set<String>,
        thumbnailIds: List<String> = emptyList()
    ): String {
        require(paths.isNotEmpty()) { "Nothing to delete." }
        paths.forEach { require(isDeletableBookPath(it, allowed)) { "Refusing to delete a file that isn't a book on the Kindle." } }
        val lines = mutableListOf<String>()
        for (path in paths.distinct()) {
            val q = shellQuote(path)
            lines += "if rm -f -- $q && [ ! -e $q ]; then printf 'OK|%s\\n' $q; else printf 'FAIL|%s\\n' $q; fi"
            if (deleteSidecars) {
                val sdr = sidecarFor(path) ?: continue
                val sq = shellQuote(sdr)
                lines += "if [ -d $sq ]; then if rm -rf -- $sq && [ ! -e $sq ]; then printf 'OK|%s\\n' $sq; " +
                    "else printf 'FAIL|%s\\n' $sq; fi; fi"
            }
        }
        for (id in thumbnailIds.distinct()) {
            val thumb = thumbnailPath(id) ?: continue
            lines += "rm -f -- ${shellQuote(thumb)} >/dev/null 2>&1"
        }
        lines += "true"
        return lines.joinToString("\n")
    }

    data class Outcome(val deleted: List<String>, val failed: List<String>, val sidecarsFailed: List<String>) {
        val allOk: Boolean get() = failed.isEmpty() && sidecarsFailed.isEmpty()
    }

    /** Reads the `OK|` / `FAIL|` lines. A requested book with no line at all counts as failed. */
    fun parseOutput(stdout: String, requested: List<String>): Outcome {
        val ok = mutableSetOf<String>()
        val fail = mutableSetOf<String>()
        for (raw in stdout.lines()) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("OK|") -> ok += line.removePrefix("OK|")
                line.startsWith("FAIL|") -> fail += line.removePrefix("FAIL|")
            }
        }
        val books = requested.distinct()
        val deleted = books.filter { it in ok }
        val failed = books.filter { it !in ok }
        val sidecarsFailed = fail.filter { it.endsWith(".sdr") && it !in books }
        return Outcome(deleted, failed, sidecarsFailed)
    }
}
