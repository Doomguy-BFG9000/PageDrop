package app.pagedrop.discover

import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal object TextUtil {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun unescapeHtml(s: String): String {
        if ('&' !in s) return s
        val named = mapOf(
            "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
            "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
            "ndash" to "–", "mdash" to "—", "hellip" to "…",
        )
        return Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);").replace(s) { m ->
            val v = m.groupValues[1]
            when {
                v.startsWith("#x") -> v.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                v.startsWith("#") -> v.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> named[v]
            } ?: m.value
        }
    }

    fun stripTags(s: String): String =
        unescapeHtml(s.replace(Regex("<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]*>"), ""))
            .lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()

    private val authorNamePart = Regex("[\\p{L}\\p{M}\\s.'’-]+")
    private val authorQualifiers = setOf(
        "jr", "sr", "ii", "iii", "iv", "ca", "fl",
        "inc", "incorporated", "ltd", "limited", "llc", "corp", "corporation", "co", "company",
        "press", "university", "institute", "foundation", "society", "association",
        "organization", "organisation", "committee", "department", "bureau",
    )

    /**
     * Cleans catalog names and dates, e.g. "Shelley, Mary Wollstonecraft (1797-1851)".
     * [simpleOnly] only reverses a plain "Last, First" name, retaining dates, suffixes and common organization qualifiers.
     */
    fun displayAuthor(raw: String, simpleOnly: Boolean = false): String {
        if (simpleOnly) {
            val parts = raw.split(',')
            if (parts.size != 2 || parts.any { part ->
                    !authorNamePart.matches(part.trim()) || part.none { it.isLetter() } ||
                        words(part).any { it in authorQualifiers }
                }) return raw
        }
        var s = raw.replace(Regex("\\([^)]*\\)"), " ")
        s = s.replace(Regex(",?\\s*(\\d{3,4}\\??|ca\\.?\\s*\\d+|fl\\.).*$"), "")
        s = s.replace(Regex("\\s+"), " ").trim().trim(',').trim()
        val parts = s.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return if (parts.size == 2) "${parts[1]} ${parts[0]}" else parts.joinToString(" ").ifEmpty { s }
    }

    fun deAccent(s: String): String =
        Normalizer.normalize(
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("(?<=\\p{IsLatin})\\p{M}+"), ""),
            Normalizer.Form.NFC,
        )

    fun words(s: String): List<String> =
        deAccent(s).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    /** Title with subtitles (after ; or :) and a leading article removed, as a word list string. */
    fun normTitle(title: String): String {
        val main = title.substringBefore(';').substringBefore(':').substringBefore(" (")
            .let { Regex(",\\s*or[, ].*$", RegexOption.IGNORE_CASE).replace(it, "") }
        val w = words(main)
        return (if (w.size > 1 && w.first() in setOf("the", "a", "an")) w.drop(1) else w).joinToString(" ")
    }

    fun surname(author: String): String = words(author.substringBefore(',').substringBefore(';')).lastOrNull().orEmpty()

    fun dedupeKey(title: String, author: String): String = normTitle(title) + "|" + surname(author)

    fun yearOf(raw: String?): Int? = raw?.let { Regex("(?<!\\d)(\\d{4})(?!\\d)").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        ?.takeIf { it in 1000..2200 }

    fun shortLanguage(raw: String?): String? {
        val l = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return when (l) {
            "eng", "english" -> "en"
            "hin", "hindi", "हिंदी", "हिन्दी" -> "hi"
            "urd", "urdu", "उर्दू" -> "ur"
            "ben", "bengali", "bangla" -> "bn"
            "mar", "marathi" -> "mr"
            "san", "sanskrit" -> "sa"
            "tam", "tamil" -> "ta"
            "tel", "telugu" -> "te"
            "guj", "gujarati" -> "gu"
            "pan", "punjabi" -> "pa"
            else -> l
        }
    }

    fun cleanQuery(q: String): String = q.trim().replace(Regex("\\s+"), " ")
}
