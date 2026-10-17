package app.pagedrop.ui.kindle

import app.pagedrop.data.local.database.Book
import app.pagedrop.tools.dashboard.DeviceCommandRunner.KindleFile
import app.pagedrop.tools.dashboard.DeviceCommandRunner.KindleLibraryScan
import app.pagedrop.transfer.sftp.KindleSftpClient

/** Result of a "Check Kindle library" run, ready for the UI. */
data class KindleLibraryReport(
    val rescanMethod: String?,
    val koreaderRunning: Boolean,
    /** Book files only (KUAL / clippings removed). */
    val files: List<KindleFile>,
    /** Paths of [files] that belong to a PageDrop library book. */
    val pageDropPaths: Set<String>,
    /** Distinct books on the Kindle (the same title in both folders counts once). */
    val bookCount: Int,
    /** Library books found on the Kindle. */
    val pageDropCount: Int
)

/** Pure matching of the app library against what is on the Kindle. */
object KindleLibraryMatcher {
    data class Changes(val markTransferred: List<Int>, val clearTransferred: List<Int>, val timestamp: Long)

    private val articles = listOf("the", "a", "an")

    private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
    private fun stem(name: String) = name.substringBeforeLast('.', name)

    /** Same extension family: the app uploads .azw3 as .azw. */
    private fun extFamily(name: String) = ext(name).let { if (it == "azw3") "azw" else it }

    /** Lower-case, no leading article, letters and digits only. */
    internal fun normalize(text: String): String {
        var t = text.lowercase().trim()
        for (a in articles) if (t.startsWith("$a ")) { t = t.removePrefix("$a "); break }
        return t.filter { it.isLetterOrDigit() }
    }

    private fun candidateNames(book: Book): List<String> {
        val fileName = book.fileName
        val names = mutableListOf(fileName, KindleSftpClient.kindleFileName(fileName))
        val exts = listOf(ext(fileName), ext(KindleSftpClient.kindleFileName(fileName))).filter { it.isNotEmpty() }.distinct()
        if (book.title.isNotBlank()) {
            for (e in exts) {
                names += if (book.author.isNotBlank()) "${book.title} - ${book.author}.$e" else "${book.title}.$e"
            }
        }
        return names.distinct()
    }

    private fun matches(book: Book, file: KindleFile, candidates: List<String>): Boolean {
        if (candidates.any { it.equals(file.name, ignoreCase = true) }) return true
        val fileNorm = normalize(stem(file.name))
        if (fileNorm.length < 3) return false
        val candNorms = candidates.map { normalize(stem(it)) to extFamily(it) }
        // Same normalized name and format.
        if (candNorms.any { (n, e) -> n == fileNorm && e == extFamily(file.name) }) return true
        // Same size and similar name (one contains the other), any format.
        if (book.fileSize > 0 && book.fileSize == file.sizeBytes) {
            if (candNorms.any { (n, _) -> n.length >= 4 && (n.contains(fileNorm) || fileNorm.contains(n)) }) return true
        }
        return false
    }

    /** For each library book found on the Kindle, the matching files (either folder). */
    fun matchBooks(books: List<Book>, files: List<KindleFile>): Map<Int, List<KindleFile>> {
        val bookFiles = files.filter { it.isBook }
        val result = LinkedHashMap<Int, List<KindleFile>>()
        for (book in books) {
            val candidates = candidateNames(book)
            val found = bookFiles.filter { matches(book, it, candidates) }
            if (found.isNotEmpty()) result[book.uid] = found
        }
        return result
    }

    /**
     * Newly found books get [now] as lastTransferred (existing timestamps are kept);
     * books with a timestamp that are gone from the Kindle are cleared.
     */
    fun reconcile(books: List<Book>, matches: Map<Int, List<KindleFile>>, now: Long): Changes {
        val mark = books.filter { it.uid in matches && it.lastTransferred == null }.map { it.uid }
        val clear = books.filter { it.uid !in matches && it.lastTransferred != null }.map { it.uid }
        return Changes(mark, clear, now)
    }

    fun report(scan: KindleLibraryScan, matches: Map<Int, List<KindleFile>>): KindleLibraryReport {
        val files = scan.files.filter { it.isBook }
        val bookCount = files.map { normalize(stem(it.name)) }.distinct().size
        return KindleLibraryReport(
            rescanMethod = scan.rescanMethod,
            koreaderRunning = scan.koreaderRunning,
            files = files,
            pageDropPaths = matches.values.flatten().map { it.path }.toSet(),
            bookCount = bookCount,
            pageDropCount = matches.size
        )
    }
}
