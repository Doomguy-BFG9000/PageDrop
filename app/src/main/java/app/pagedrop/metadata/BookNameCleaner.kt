package app.pagedrop.metadata

import java.text.Normalizer

/** The result of cleaning a raw file name / title into something searchable. */
data class ParsedBookName(
    /** Cleaned title guess (may be blank when the name carried no usable words). */
    val title: String,
    /** Author guess taken from a "Title - Author" style name, if any. */
    val author: String?,
    val isbn: String?,
    /** The raw input looked like a file name rather than a real title. */
    val looksLikeFilename: Boolean,
    /** "A - B": we cannot tell whether A or B is the title. Search both ways. */
    val orderAmbiguous: Boolean,
    /** Words were thrown away (ids, hashes) or guessed (word splitting); matches are less trustworthy. */
    val lossy: Boolean,
) {
    val isSearchable: Boolean get() = isbn != null || title.isNotBlank()
}

/** Turns names like `the-great-gatsby` or `Ticket_TK1010718726t83` into search queries. */
object BookNameCleaner {
    private val extension = Regex("\\.(epub|pdf|mobi|azw3|azw|kfx|txt|prc|docx?)$", RegexOption.IGNORE_CASE)
    private val bracketNoise = Regex("\\[[^\\]]*]|\\((?:z-?lib[^)]*|libgen[^)]*|[\\w-]+\\.\\w{2,4}|\\d+|retail[^)]*|epub|pdf|mobi|azw3?)\\)", RegexOption.IGNORE_CASE)
    private val dashSplit = Regex("\\s+[-\u2013\u2014]\\s+")
    private val idLike = Regex("^[A-Za-z]{0,3}\\d{5,}[A-Za-z0-9]*$")
    private val isbn13 = Regex("(?<![0-9])97[89][0-9]{10}(?![0-9])")
    private val isbn10 = Regex("(?<![0-9])[0-9]{9}[0-9Xx](?![0-9A-Za-z])")

    private const val TLDS = "com|org|net|sk|is|io|pub|info|cc|se|st|li|rs|to|me|app|ru|cn"
    private val siteNoise = Regex(
        "(?<![A-Za-z0-9])(?:z[-_. ]?lib(?:rary)?|1lib|b-ok|bookzz|libgen|oceanofpdf|pdfdrive|pdfcoffee|ebin|dokumen|vdoc|epdf|archive\\.org|scribd|bookfi|ebooksz|anna'?s[-_ ]archive)(?:\\.(?:$TLDS))*(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    private val domainNoise = Regex("(?<![A-Za-z0-9])(?:https?://)?(?:www\\.)?[A-Za-z0-9-]+\\.(?:$TLDS)(?![A-Za-z0-9])|www\\.\\S+", RegexOption.IGNORE_CASE)
    private val noiseWord = Regex("^(?:ebooks?|downloads?|downloaded|pdf|epub|mobi|azw3?|kfx|retail|v\\d+(?:\\.\\d+)*|ver\\d+|vk|copy|scan|scanned|ocr|true|ebok)$", RegexOption.IGNORE_CASE)
    private val bracketHasNoise = Regex("(?:ebook|e-book|pdf|epub|mobi|azw|retail|free|download|lib|libgen|www|\\.(?:$TLDS)|v\\d)", RegexOption.IGNORE_CASE)
    private val asin = Regex("^B0[0-9A-Z]{8}$")
    private val productCode = Regex("^[A-Z0-9]{8,}$")
    private val byPattern = Regex("^(.+?)\\s+by\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val trailingParenAuthor = Regex("^(.*\\S)\\s*\\(([^()]+)\\)$")
    private val authorStopFirst = setOf("the", "a", "an", "of", "and", "my", "me", "us", "you", "him", "her", "it", "them", "god")

    /** True when [text] carries file-sharing-site or format noise (`z-lib.org`, `[PDF]`, `www.x.com`). */
    fun hasNoise(text: String): Boolean {
        if (siteNoise.containsMatchIn(text) || domainNoise.containsMatchIn(text)) return true
        return Regex("[\\[(]([^\\])]*)[\\])]").findAll(text).any { bracketHasNoise.containsMatchIn(it.groupValues[1]) }
    }

    /** "Title by Author" with a plausible author part, or null. */
    fun splitByAuthor(text: String): Pair<String, String>? {
        val m = byPattern.matchEntire(text.trim()) ?: return null
        val title = m.groupValues[1].trim()
        val author = m.groupValues[2].trim()
        val words = author.split(' ').filter { it.isNotEmpty() }
        if (title.isEmpty() || words.size !in 2..4) return null
        if (words.first().lowercase() in authorStopFirst) return null
        if (words.any { w -> w.any { it.isDigit() } }) return null
        return title to author
    }

    private val placeholderAuthors = setOf("", "unknown", "unknown author", "unknown_author", "n/a", "na", "none", "author", "-")

    /** Words used to split run-together lowercase names such as `flowersforalgernon`. */
    private val connectors = setOf(
        "the", "and", "for", "with", "from", "into", "that", "this", "you", "are", "was", "not", "all",
        "his", "her", "our", "your", "over", "under", "who", "what", "when", "where", "how", "man", "men",
        "old", "sea", "war", "art", "day", "night", "love", "life", "death", "book", "story", "world",
    )

    fun isPlaceholderAuthor(author: String?): Boolean =
        author == null || author.trim().lowercase() in placeholderAuthors

    /** Extracts a clean ISBN-10/13 (digits only) when [text] contains one as a standalone token. */
    fun extractIsbn(text: String): String? {
        val compact = text.replace(Regex("(?<=[0-9Xx])[\\s-](?=[0-9Xx])"), "")
        isbn13.find(compact)?.value?.let { return it }
        return isbn10.find(compact)?.value?.uppercase()
    }

    fun looksLikeFilename(rawTitle: String): Boolean {
        val s = rawTitle.replace(extension, "").trim()
        if (s.isEmpty()) return true
        if ('_' in s) return true
        val noSpaces = s.none { it.isWhitespace() }
        if (noSpaces && (s.contains('-') || s.contains('.')) && s.any { it.isLetter() }) return true
        val digits = s.count { it.isDigit() }
        if (s.length >= 6 && digits.toDouble() / s.length >= 0.4) return true
        if (Regex("^[A-Za-z]{1,4}\\d{6,}").containsMatchIn(s)) return true
        if (asin.matches(s)) return true
        if (productCode.matches(s) && s.any { it.isDigit() }) return true
        if (hasNoise(s)) return true
        if (noSpaces && s.length >= 12 && s.all { it.isLowerCase() || it.isDigit() }) return true
        return false
    }

    /** True when the user is probably better off with an online lookup for this book. */
    fun isMissingMetadata(title: String, author: String?): Boolean =
        isPlaceholderAuthor(author) || looksLikeFilename(title) || dashSplit.containsMatchIn(title) ||
            (author.isNullOrBlank() && splitByAuthor(title) != null)

    fun parse(raw: String): ParsedBookName {
        val looks = looksLikeFilename(raw)
        var s = raw.replace(extension, "")
        val isbn = extractIsbn(s)
        var lossy = false
        var parenAuthor: String? = null
        s = siteNoise.replace(s, " ")
        s = domainNoise.replace(s, " ")
        s = s.replace(Regex("\\[[^\\]]*]"), " ")
        // Parentheses: drop noise / numbers / series info; keep a trailing "(Author Name)".
        s = Regex("\\(([^()]*)\\)").replace(s) { m ->
            val inner = m.groupValues[1].trim()
            if (bracketHasNoise.containsMatchIn(inner) || inner.isEmpty() || inner.any { it.isDigit() } || inner.contains(',')) " " else m.value
        }
        trailingParenAuthor.matchEntire(s.trim())?.let { m ->
            val inner = m.groupValues[2].trim()
            val words = inner.split(' ').filter { it.isNotEmpty() }
            if (words.size in 1..4 && words.all { it.first().isUpperCase() || it.first() == '.' }) {
                parenAuthor = inner
                s = m.groupValues[1]
            }
        }
        s = s.replace(Regex("[()]"), " ").replace('_', ' ').replace('+', ' ')
        s = s.replace(Regex("\\s+"), " ").trim()

        val hyphens = Regex("(?<=\\w)-(?=\\w)").findAll(s).count()
        if (s.none { it.isWhitespace() } || (hyphens >= 2 && !dashSplit.containsMatchIn(s))) {
            s = s.replace(Regex("[-.]+"), " ")
            if (s.any { it.isLowerCase() } && s.any { it.isUpperCase() }) {
                s = s.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
            }
        }
        run {
            val tokens = s.split(' ').filter { it.isNotBlank() }.toMutableList()
            var removed = false
            var i = 0
            while (i < tokens.size) {
                val t = tokens[i]
                val isEBook = t.equals("e", ignoreCase = true) && tokens.getOrNull(i + 1)?.lowercase() in setOf("book", "books")
                if (isEBook) { tokens.removeAt(i); tokens.removeAt(i); removed = true; continue }
                if (noiseWord.matches(t)) { tokens.removeAt(i); removed = true; continue }
                i++
            }
            while (tokens.isNotEmpty() && tokens.last() == "-") tokens.removeAt(tokens.lastIndex)
            while (tokens.isNotEmpty() && tokens.first() == "-") tokens.removeAt(0)
            if (removed) {
                while (tokens.isNotEmpty() && tokens.last().lowercase() in setOf("free", "-")) tokens.removeAt(tokens.lastIndex)
                while (tokens.isNotEmpty() && tokens.first().lowercase() in setOf("free", "-")) tokens.removeAt(0)
            }
            s = tokens.joinToString(" ")
        }
        val byParts = if (parenAuthor == null && !dashSplit.containsMatchIn(s)) splitByAuthor(s) else null
        if (byParts != null) {
            s = byParts.first
            parenAuthor = byParts.second
        }

        val parts = dashSplit.split(s, limit = 2)
        var title = parts[0]
        var author = parts.getOrNull(1) ?: parenAuthor
        val ambiguous = parts.size > 1

        fun dropIds(text: String): String {
            val tokens = text.split(' ').filter { it.isNotBlank() }
            val kept = tokens.filterNot { token ->
                val digits = token.count { it.isDigit() }
                idLike.matches(token) || asin.matches(token) || (token.length >= 6 && digits.toDouble() / token.length >= 0.4)
            }
            if (kept.size != tokens.size) lossy = true
            return kept.joinToString(" ")
        }
        title = dropIds(title).trim()
        author = author?.let { dropIds(it).trim() }?.takeIf { it.isNotBlank() }

        if (!title.contains(' ') && title.length >= 12 && title.all { it.isLowerCase() }) {
            val split = segment(title)
            if (split != null) {
                title = split
                lossy = true
            }
        }
        return ParsedBookName(title, author, isbn, looks, ambiguous && author != null, lossy)
    }

    /** Splits `flowersforalgernon` into `flowers for algernon` using known connector words. */
    internal fun segment(word: String): String? {
        val n = word.length
        val cost = IntArray(n + 1) { Int.MAX_VALUE }
        val prev = IntArray(n + 1) { -1 }
        cost[0] = 0
        for (i in 0 until n) {
            if (cost[i] == Int.MAX_VALUE) continue
            for (j in i + 3..n) {
                val piece = word.substring(i, j)
                val c = cost[i] + if (piece in connectors) 0 else piece.length + 2
                if (c < cost[j]) { cost[j] = c; prev[j] = i }
            }
        }
        if (cost[n] == Int.MAX_VALUE) return null
        val pieces = ArrayDeque<String>()
        var j = n
        while (j > 0) { pieces.addFirst(word.substring(prev[j], j)); j = prev[j] }
        val knownCount = pieces.count { it in connectors }
        if (knownCount == 0 || pieces.size < 2) return null
        return pieces.joinToString(" ")
    }

    /**
     * Extra search strings for names that are a single run-together word (`greatgatsby` -> `great gatsby`).
     * Last resort: only use after the plain query found nothing.
     */
    fun extraQueries(raw: String): List<String> {
        val parsed = parse(raw)
        val t = parsed.title
        if (t.isBlank() || t.contains(' ') || t.length < 8 || !t.all { it.isLetter() }) return emptyList()
        val split = segmentWords(t.lowercase()) ?: return emptyList()
        return listOf(split)
    }

    /** DP word segmentation over [commonWords]; null unless known words cover enough of the input. */
    internal fun segmentWords(word: String): String? {
        val n = word.length
        val inf = Int.MAX_VALUE / 2
        val cost = IntArray(n + 1) { inf }
        val prev = IntArray(n + 1) { -1 }
        cost[0] = 0
        for (i in 0 until n) {
            if (cost[i] >= inf) continue
            for (j in i + 2..n) {
                val piece = word.substring(i, j)
                val known = piece in commonWords || piece in connectors
                if (!known && piece.length < 3) continue
                val c = cost[i] + if (known) 1 else piece.length + 3
                if (c < cost[j]) { cost[j] = c; prev[j] = i }
            }
        }
        if (cost[n] >= inf) return null
        val pieces = ArrayDeque<String>()
        var j = n
        while (j > 0) { pieces.addFirst(word.substring(prev[j], j)); j = prev[j] }
        val knownChars = pieces.filter { it in commonWords || it in connectors }.sumOf { it.length }
        if (pieces.size < 2 || knownChars * 10 < n * 4) return null
        return pieces.joinToString(" ")
    }

    private val commonWords: Set<String> = (
        "great little big small black white red blue green golden silver dark light bright old new young first last " +
        "good bad long short high low deep wide hidden secret lost found broken silent wild free true false dead living " +
        "king queen prince princess lord lady knight dragon dragons wizard witch ghost angel devil god gods heart soul mind " +
        "body blood bone bones fire water earth air wind storm rain snow sun moon star stars sky ocean river mountain forest " +
        "garden house home room door window road street city town village island castle tower bridge wall gate " +
        "time year years day days night nights morning evening summer winter spring autumn season dream dreams memory memories " +
        "game games hunger fire catching mockingjay harry potter hobbit rings ring lord sword crown throne rose roses " +
        "book books story stories tale tales letter letters diary journal poems poem song songs music dance " +
        "girl girls boy boys man men woman women child children mother father brother sister daughter son wife husband friend friends family " +
        "love hate war peace death life living world worlds universe space galaxy planet mars earth " +
        "kill killing murder crime mystery thriller detective case cases spy agent " +
        "art science history theory power habits habit atomic mind mindset thinking think fast slow rich poor money wealth " +
        "way ways path journey road step steps rule rules law laws code secret secrets truth lie lies " +
        "alchemist shadow shadows echo echoes silence sound voice voices eyes face hands hand feet " +
        "call calls cry cries run running walk walking fall falling rise rising born live lives die dying " +
        "pride prejudice sense sensibility emma persuasion paradise inferno purgatory divine comedy " +
        "animal farm brave new world catcher rye mockingbird kill lord flies fahrenheit handmaid tale " +
        "one two three four five six seven eight nine ten hundred thousand million " +
        "before after between beyond behind beneath above below inside outside through across along around against " +
        "making made make makes take taken taking give given giving going gone come coming comes " +
        "man's woman's winter's summer's"
        ).split(' ').filter { it.length >= 3 }.toSet()

    // ── Text normalisation shared with the ranker ─────────────────────────────

    internal fun normalizeTokens(text: String): List<String> {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace("&", " and ")
        return folded.replace(Regex("[^a-z0-9]+"), " ").trim().split(' ').filter { it.isNotEmpty() }
    }
}
