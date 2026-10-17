package app.pagedrop.discover

/**
 * Standard Ebooks. The OPDS feed needs a paid login, so this reads the public HTML search page.
 *
 * Verified live: each result is an `<li typeof="schema:Book" about="/ebooks/<slug>">`; the book's files live at
 * `/ebooks/<slug>/downloads/<slug segments joined by _>.azw3|.epub` and MUST be requested with `?source=download`,
 * otherwise the server answers with an HTML "Your download has started" page instead of the file.
 */
internal class StandardEbooksSource(private val http: DiscoverHttp) : BookSource {
    override val kind = SourceKind.STANDARD_EBOOKS
    override val name = "Standard Ebooks"

    override fun search(query: String): List<BookResult> {
        val reply = http.get("https://standardebooks.org/ebooks?query=${TextUtil.enc(query)}", accept = "text/html")
            .requireOk(name)
        return parse(reply.body)
    }

    companion object {
        private const val BASE = "https://standardebooks.org"
        private const val MAX_RESULTS = 10
        private val ITEM = Regex("""<li\s+typeof="schema:Book"\s+about="(/ebooks/[^"]+)"[^>]*>(.*?)</li>""", RegexOption.DOT_MATCHES_ALL)
        private val TITLE = Regex("""<p>\s*<a[^>]*property="schema:url"[^>]*>\s*<span property="schema:name">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        private val AUTHOR_BLOCK = Regex("""<p[^>]*class="author"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
        private val NAME = Regex("""<span property="schema:name">(.*?)</span>""", RegexOption.DOT_MATCHES_ALL)
        private val SAFE_SLUG = Regex("^[A-Za-z0-9._\\-/]+$")

        fun parse(html: String): List<BookResult> =
            ITEM.findAll(html).mapNotNull { m ->
                val path = m.groupValues[1]
                val slug = path.removePrefix("/ebooks/").trim('/')
                if (slug.isEmpty() || !SAFE_SLUG.matches(slug)) return@mapNotNull null
                val inner = m.groupValues[2]
                val title = TITLE.find(inner)?.groupValues?.get(1)?.let(TextUtil::stripTags)?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val author = AUTHOR_BLOCK.findAll(inner)
                    .mapNotNull { NAME.find(it.groupValues[1])?.groupValues?.get(1)?.let(TextUtil::stripTags) }
                    .filter { it.isNotBlank() }.joinToString(", ")
                BookResult(
                    id = "standard_ebooks:$slug",
                    source = SourceKind.STANDARD_EBOOKS,
                    sourceName = "Standard Ebooks",
                    title = title,
                    author = author,
                    language = "en",
                    coverUrl = "$BASE/ebooks/$slug/downloads/cover.jpg",
                    options = options(slug),
                    websiteUrl = "$BASE/ebooks/$slug",
                )
            }.take(MAX_RESULTS).toList()

        fun options(slug: String): List<DownloadOption> {
            val file = slug.replace('/', '_')
            val base = "$BASE/ebooks/$slug/downloads/$file"
            return listOf(
                DownloadOption(BookFormat.AZW3, "$base.azw3?source=download", label = "Kindle (AZW3)"),
                DownloadOption(BookFormat.EPUB, "$base.epub?source=download", label = "EPUB"),
            )
        }
    }
}
