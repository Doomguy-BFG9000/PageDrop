package app.pagedrop.metadata

/** What we searched for; used both to build requests and to score candidates. */
data class LookupQuery(
    val title: String,
    val author: String = "",
    val isbn: String? = null,
    /** "A - B" file names: either order is plausible, score both. */
    val orderAmbiguous: Boolean = false,
    /** The query came from lossy cleaning of a file name; confidence is dampened. */
    val lossy: Boolean = false,
    val guessedFromFilename: Boolean = false,
) {
    val isSearchable: Boolean get() = isbn != null || title.isNotBlank() || author.isNotBlank()

    companion object {
        /** Builds a query from user/file-supplied [title] and [author], cleaning file-name style input. */
        fun from(title: String, author: String = ""): LookupQuery {
            val cleanedAuthor = if (BookNameCleaner.isPlaceholderAuthor(author)) "" else author.trim()
            val needsParsing = BookNameCleaner.looksLikeFilename(title) ||
                (cleanedAuthor.isEmpty() && (title.contains(" - ") || BookNameCleaner.splitByAuthor(title) != null))
            val explicitIsbn = BookNameCleaner.extractIsbn(title)
            if (!needsParsing && explicitIsbn == null) return LookupQuery(title.trim(), cleanedAuthor)
            val parsed = BookNameCleaner.parse(title)
            val a = cleanedAuthor.ifEmpty { parsed.author.orEmpty() }
            return LookupQuery(
                title = parsed.title,
                author = a,
                isbn = parsed.isbn,
                orderAmbiguous = cleanedAuthor.isEmpty() && parsed.orderAmbiguous,
                lossy = parsed.lossy,
                guessedFromFilename = parsed.looksLikeFilename,
            )
        }
    }
}

/** Scores candidates against a [LookupQuery]. Pure and unit-tested. */
object BookMetadataRanker {
    const val HIGH_CONFIDENCE = 0.8f

    fun rank(query: LookupQuery, candidates: List<BookMetadataCandidate>): List<BookMetadataCandidate> =
        candidates.map { it.copy(confidence = confidence(query, it)) }
            .withIndex()
            .sortedWith(compareByDescending<IndexedValue<BookMetadataCandidate>> { it.value.confidence }.thenBy { it.index })
            .map { it.value }

    fun confidence(query: LookupQuery, candidate: BookMetadataCandidate): Float {
        if (query.isbn != null && candidate.isbn != null && query.isbn == candidate.isbn.filter { it.isLetterOrDigit() }.uppercase()) {
            return 0.95f
        }
        val straight = score(query.title, query.author, candidate)
        val swapped = if (query.orderAmbiguous && query.author.isNotBlank()) {
            score(query.author, query.title, candidate)
        } else 0.0
        var c = maxOf(straight, swapped)
        if (query.lossy) c *= 0.7
        if (query.guessedFromFilename && BookNameCleaner.normalizeTokens(query.title).size == 1) c *= 0.85
        return c.coerceIn(0.0, 1.0).toFloat()
    }

    private fun score(title: String, author: String, candidate: BookMetadataCandidate): Double {
        if (title.isBlank()) return 0.0
        val t = titleSimilarity(title, candidate.title)
        val cover = if (candidate.coverUrl != null) 0.05 else 0.0
        return if (author.isBlank()) {
            // Cannot verify the author, so never reach full certainty.
            t * 0.85 + cover
        } else {
            t * 0.8 + authorMatch(author, candidate.author) * 0.15 + cover
        }
    }

    private val articles = setOf("the", "a", "an")

    fun titleSimilarity(query: String, candidateTitle: String): Double {
        val q = BookNameCleaner.normalizeTokens(query).filterNot { it in articles }
        if (q.isEmpty()) return 0.0
        val variants = listOf(candidateTitle, candidateTitle.substringBefore(':'), candidateTitle.substringBefore(" - "), candidateTitle.substringBefore('('))
            .distinct()
        return variants.maxOf { v ->
            val c = BookNameCleaner.normalizeTokens(v).filterNot { it in articles }
            if (c.isEmpty()) 0.0 else sim(q, c)
        }
    }

    private fun sim(q: List<String>, c: List<String>): Double {
        if (q == c) return 1.0
        val matched = q.count { qt -> c.any { tokensEqual(qt, it) } }
        val dice = 2.0 * matched / (q.size + c.size)
        val qInC = matched == q.size
        val cInQ = c.all { ct -> q.any { tokensEqual(it, ct) } }
        val subset = if ((qInC && q.size >= 2) || (cInQ && c.size >= 2)) 0.8 else 0.0
        return maxOf(dice, subset)
    }

    private fun tokensEqual(a: String, b: String): Boolean =
        a == b || (a.length >= 5 && b.length >= 5 && kotlin.math.abs(a.length - b.length) <= 1 && levenshtein(a, b) <= 1)

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }

    /** 1.0 when every query-author word (e.g. surname) appears in the candidate authors, else the fraction. */
    fun authorMatch(queryAuthor: String, candidateAuthor: String): Double {
        val q = BookNameCleaner.normalizeTokens(queryAuthor).filter { it.length >= 2 }
        val c = BookNameCleaner.normalizeTokens(candidateAuthor)
        if (q.isEmpty() || c.isEmpty()) return 0.0
        return q.count { qt -> c.any { tokensEqual(qt, it) } }.toDouble() / q.size
    }
}
