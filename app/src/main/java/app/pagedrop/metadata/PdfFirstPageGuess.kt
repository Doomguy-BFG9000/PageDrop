package app.pagedrop.metadata

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Low-confidence title/author guess taken from the first pages' text of a PDF (title page, "By Author"). */
object PdfFirstPageGuess {
    data class Guess(val title: String, val author: String?, val hadByLine: Boolean)

    private const val MAX_BYTES = 150L * 1024 * 1024

    private val junkLine = Regex(
        "download|free|e-?books?|www\\.|https?:|\\.(com|org|net)\\b|copyright|\u00a9|isbn|all rights|published|publisher|" +
            "table of contents|^contents$|^chapter\\b|^page \\d+|project gutenberg|^\\d+$|^[ivxlc]+$|scanned|www|@",
        RegexOption.IGNORE_CASE,
    )
    private val byLine = Regex("^(?:written\\s+)?by\\s+(\\p{L}[\\p{L}.'\u2019 -]{1,60})$", RegexOption.IGNORE_CASE)
    private val bareBy = Regex("^by$", RegexOption.IGNORE_CASE)
    private val subtitleOnly = Regex("^(?:a|an|the)?\\s*(?:novel|story|tale|memoir|play|classic|novella|romance|thriller)s?$", RegexOption.IGNORE_CASE)
    private val connectiveEnd = Regex("\\b(?:and|the|of|a|an|in|to|for|with|on|at|or)$", RegexOption.IGNORE_CASE)
    private val smallWords = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "with")

    /** Reads pages 1..[pages] and returns the first confident guess; null on any failure (encrypted, no text, PDFBox not initialised). */
    fun guess(file: File, pages: Int = 3): Guess? = try {
        if (file.length() > MAX_BYTES) null else PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) return@use null
            var fallback: Guess? = null
            for (p in 1..minOf(pages, doc.numberOfPages)) {
                val stripper = PDFTextStripper().apply { startPage = p; endPage = p }
                val g = guessFromText(stripper.getText(doc)) ?: continue
                if (g.hadByLine) return@use g
                if (fallback == null) fallback = g
            }
            fallback
        }
    } catch (e: Exception) {
        null
    } catch (e: LinkageError) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** Pure heuristic over one page of text. */
    fun guessFromText(text: String): Guess? {
        val lines = text.lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }.take(40)
        if (lines.isEmpty()) return null

        fun usable(line: String) = line.length in 2..90 && !junkLine.containsMatchIn(line) && !subtitleOnly.matches(line) &&
            line.count { it.isLetter() } >= 2

        for ((i, line) in lines.withIndex()) {
            var author: String? = null
            var authorLine = i
            byLine.matchEntire(line)?.let { author = it.groupValues[1].trim() }
            if (author == null && bareBy.matches(line)) {
                author = lines.getOrNull(i + 1)?.takeIf { it.split(' ').size in 2..5 }
                authorLine = i + 1
            }
            val a = author ?: continue
            if (a.split(' ').size !in 2..5 || junkLine.containsMatchIn(a) || a.any { it.isDigit() }) continue
            // Title lines: contiguous usable lines right before the by-line.
            val before = lines.subList(maxOf(0, i - 4), i).filter { usable(it) || subtitleOnly.matches(it) }.filter { usable(it) }
            if (before.isEmpty()) continue
            val l1 = before.last()
            val title = if (before.size >= 2 && connectiveEnd.containsMatchIn(before[before.size - 2])) "${before[before.size - 2]} $l1" else l1
            return Guess(tidy(title), tidyAuthor(a), hadByLine = authorLine >= i)
        }

        val first = lines.take(8).firstOrNull { usable(it) && it.split(' ').size in 1..10 && !it.endsWith('.') }
        return first?.let { Guess(tidy(it), null, hadByLine = false) }
    }

    private fun tidyAuthor(a: String): String = if (a.any { it.isLowerCase() }) a else titleCase(a)

    private fun tidy(t: String): String {
        val s = t.trim().trim('"', '\u201c', '\u201d', '*', '-', '\u2014')
        return if (s.any { it.isLowerCase() }) s else titleCase(s)
    }

    private fun titleCase(s: String): String =
        s.lowercase().split(' ').mapIndexed { i, w ->
            if (i > 0 && w in smallWords) w else w.replaceFirstChar { it.titlecase() }
        }.joinToString(" ")
}
