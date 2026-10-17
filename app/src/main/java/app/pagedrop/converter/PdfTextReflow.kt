package app.pagedrop.converter

/**
 * A block of reflowed text extracted from a PDF.
 */
sealed class Block {
    data class Paragraph(val text: String) : Block()
    data class Heading(val text: String) : Block()
}

/**
 * Pure-Kotlin cleanup of raw per-page PDF text into paragraphs and headings.
 * No Android or PDFBox dependencies, so it is unit-testable on the JVM.
 *
 * Steps:
 *   1. Strip running headers/footers, page numbers and OCR margin junk
 *   2. Detect headings (CHAPTER 1, PART I, short ALL CAPS lines)
 *   3. Unwrap hard-wrapped lines into paragraphs (de-hyphenating), using blank
 *      lines, indentation and "short line ending in sentence punctuation"
 *      to detect paragraph ends
 *   4. Merge paragraphs split across page boundaries
 */
object PdfTextReflow {

    /** A line that ends a paragraph if it is shorter than this fraction of the typical line length. */
    private const val SHORT_LINE_RATIO = 0.78

    /** Looser ratio used when the next line opens a dialogue quote. */
    private const val DIALOGUE_SHORT_RATIO = 0.95

    private const val MIN_LINES_FOR_PAGE_STATS = 8
    private const val EDGE_LINES = 2

    private val PAGE_NUMBER_PATTERNS = listOf(
        Regex("""^[-–—]?\s*\d{1,5}\s*[-–—]?$"""),
        Regex("""^\d+\s*\|\s*P\s*a\s*g\s*e\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""^P\s*a\s*g\s*e\s*\|?\s*\d+\b.*$""", RegexOption.IGNORE_CASE),
        Regex("""^page\s+\d+(\s+of\s+\d+)?$""", RegexOption.IGNORE_CASE),
        Regex("""^\d+\s*/\s*\d+$"""),
    )

    private val NUMBER_WORDS = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|" +
        "fifteen|sixteen|seventeen|eighteen|nineteen|twenty(?:[- ](?:one|two|three|four|five|six|seven|eight|nine))?|" +
        "thirty|forty|fifty"

    private val CHAPTER_PATTERN = Regex(
        """^(?:(?:CHAPTER|Chapter|PART|Part|BOOK|Book)\s+(?:\d{1,3}|[IVXLC]{1,8}|(?i:$NUMBER_WORDS))\b.{0,40}|""" +
            """(?i:prologue|epilogue|introduction|preface|foreword|afterword|acknowledg(?:e)?ments))$"""
    )

    private val TERMINAL_PATTERN = Regex("""[.!?…]["'”’)\]]*$""")
    private val QUOTE_OPENERS = setOf('"', '“', '‘', '—', '―')

    fun reflow(pages: List<String>): List<Block> {
        val rawPages = pages.map { page -> page.replace("\r", "").split('\n').map { it.trimEnd() } }
        val cleaned = removeRepeatedHeaders(rawPages).map { cleanPage(it) }

        val globalRef = percentile90(cleaned.flatMap { p -> p.filter { it.isNotBlank() }.map { it.trim().length } })

        val blocks = ArrayList<Block>()
        val para = StringBuilder()
        var prev: PrevLine? = null // last body line appended to the current paragraph
        var atBoundary = true // previous line was blank / page start / heading

        fun flush() {
            val text = para.toString().trim()
            if (text.isNotEmpty()) blocks.add(Block.Paragraph(text))
            para.setLength(0)
            prev = null
        }

        for ((pageIdx, lines) in cleaned.withIndex()) {
            val nonBlank = lines.filter { it.isNotBlank() }
            val ref = if (nonBlank.size >= MIN_LINES_FOR_PAGE_STATS) {
                percentile90(nonBlank.map { it.trim().length })
            } else {
                globalRef
            }.coerceAtLeast(20)
            val baseIndent = nonBlank.minOfOrNull { leadingSpaces(it) } ?: 0

            // Page start: a paragraph may continue from the previous page.
            atBoundary = atBoundary || para.isEmpty()
            var firstOnPage = true

            for (raw in lines) {
                if (raw.isBlank()) {
                    flush()
                    atBoundary = true
                    continue
                }
                val line = raw.trim()
                val indented = leadingSpaces(raw) - baseIndent >= 2

                if (isHeading(line, atBoundary || firstOnPage, blocks.lastOrNull() is Block.Heading && para.isEmpty())) {
                    flush()
                    val last = blocks.lastOrNull()
                    if (last is Block.Heading && !CHAPTER_PATTERN.matches(line)) {
                        blocks[blocks.size - 1] = Block.Heading(last.text + " – " + line)
                    } else {
                        blocks.add(Block.Heading(line))
                    }
                    atBoundary = true
                    firstOnPage = false
                    continue
                }

                val p = prev
                if (para.isNotEmpty() && p != null && breaksBefore(p, line, indented, pageIdx, firstOnPage)) {
                    flush()
                }

                if (para.isEmpty()) {
                    para.append(line)
                } else {
                    appendJoined(para, line)
                }
                prev = PrevLine(line, line.length, ref, pageIdx)
                atBoundary = false
                firstOnPage = false
            }
        }
        flush()
        return blocks
    }

    /**
     * Renders blocks as simple MOBI-friendly HTML: one `<p>` per paragraph (no `<br/>`),
     * `<h2>` for headings with a page break before each heading (except the first block).
     */
    fun buildHtml(title: String, blocks: List<Block>): String {
        val sb = StringBuilder(64 * 1024)
        sb.append("<html><head><title>")
        sb.append(escapeHtml(title))
        sb.append("</title></head><body>\n")
        for ((i, block) in blocks.withIndex()) {
            when (block) {
                is Block.Heading -> {
                    if (i > 0) sb.append("<mbp:pagebreak/>\n")
                    sb.append("<h2>").append(escapeHtml(block.text)).append("</h2>\n")
                }
                is Block.Paragraph -> sb.append("<p>").append(escapeHtml(block.text)).append("</p>\n")
            }
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    // ---------------------------------------------------------------- internals

    private data class PrevLine(val text: String, val length: Int, val ref: Int, val page: Int)

    private fun breaksBefore(prev: PrevLine, next: String, nextIndented: Boolean, page: Int, firstOnPage: Boolean): Boolean {
        if (!isTerminal(prev.text)) return false // sentence not finished: always continue (also across pages)
        if (prev.page != page && firstOnPage) {
            // Crossing a page boundary after a finished sentence: break unless next continues lowercase.
            return !next.first().isLowerCase()
        }
        if (nextIndented) return true
        if (prev.length < prev.ref * SHORT_LINE_RATIO) return true
        if (next.first() in QUOTE_OPENERS && prev.length < prev.ref * DIALOGUE_SHORT_RATIO) return true
        return false
    }

    private fun isTerminal(line: String) = TERMINAL_PATTERN.containsMatchIn(line)

    private fun appendJoined(sb: StringBuilder, line: String) {
        val n = sb.length
        if (n >= 2 && sb[n - 1] == '-' && sb[n - 2].isLetter() && line.first().isLowerCase()) {
            sb.setLength(n - 1)
            sb.append(line)
        } else {
            sb.append(' ').append(line)
        }
    }

    private fun isHeading(line: String, standalone: Boolean, afterHeading: Boolean): Boolean {
        if (line.length > 60) return false
        if (CHAPTER_PATTERN.matches(line) && !line.endsWith(".")) return true
        if (!standalone && !afterHeading) return false
        val letters = line.count { it.isLetter() }
        if (letters < 3 || line.any { it.isLowerCase() }) return false
        if (line.length > 50 || line.split(Regex("\\s+")).size > 6) return false
        return !Regex("""[.!?,;:…]$""").containsMatchIn(line)
    }

    private fun leadingSpaces(s: String): Int {
        var n = 0
        for (c in s) {
            when (c) {
                ' ' -> n++
                '\t' -> n += 4
                else -> return n
            }
        }
        return n
    }

    private fun percentile90(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * 0.9).toInt()]
    }

    private fun normalizeKey(line: String): String =
        line.trim().lowercase().replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ")

    /** Removes lines at the top/bottom of pages that repeat (after digit normalisation) across many pages. */
    private fun removeRepeatedHeaders(pages: List<List<String>>): List<List<String>> {
        if (pages.size < 3) return pages
        val counts = HashMap<String, Int>()
        for (page in pages) {
            edgeIndices(page).filter { !CHAPTER_PATTERN.matches(page[it].trim()) }
                .map { normalizeKey(page[it]) }.toSet().forEach { key ->
                counts[key] = (counts[key] ?: 0) + 1
            }
        }
        val threshold = maxOf(3, (pages.size * 0.3).toInt())
        val repeated = counts.filter { (key, c) ->
            c >= threshold && key.length >= 2
        }.keys
        if (repeated.isEmpty()) return pages
        return pages.map { page ->
            val drop = edgeIndices(page)
                .filter { !CHAPTER_PATTERN.matches(page[it].trim()) && normalizeKey(page[it]) in repeated }.toSet()
            page.filterIndexed { i, _ -> i !in drop }
        }
    }

    /** Indices of the first/last [EDGE_LINES] non-blank lines of a page. */
    private fun edgeIndices(page: List<String>): List<Int> {
        val idx = page.indices.filter { page[it].isNotBlank() }
        return (idx.take(EDGE_LINES) + idx.takeLast(EDGE_LINES)).distinct()
    }

    /** Removes page-number lines at page edges and OCR margin junk; collapses blank runs. */
    private fun cleanPage(lines: List<String>): List<String> {
        val edges = edgeIndices(lines).toSet()
        val out = ArrayList<String>()
        for ((i, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (t.isEmpty()) {
                if (out.isNotEmpty() && out.last().isNotEmpty()) out.add("")
                continue
            }
            if (i in edges && PAGE_NUMBER_PATTERNS.any { it.matches(t) }) continue
            if (isJunk(t)) continue
            out.add(raw)
        }
        while (out.isNotEmpty() && out.last().isEmpty()) out.removeAt(out.size - 1)
        return out
    }

    /** Very short fragments typical of OCR margin noise ("Ie", ")f", "d"). Conservative. */
    private fun isJunk(t: String): Boolean {
        if (t.length > 2) return false
        if (t.all { it.isDigit() }) return false // handled by page-number rules; may be a chapter number
        if (isTerminal(t) || t in setOf("I", "a", "A", "—", "–")) return false
        return true
    }
}
