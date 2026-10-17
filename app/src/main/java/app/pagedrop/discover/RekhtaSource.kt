package app.pagedrop.discover

import org.jsoup.Jsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Both sites expose book cards in HTML, but reading/download access stays with their web reader. */
internal class RekhtaSource(private val http: DiscoverHttp, override val kind: SourceKind) : BookSource {
    init { require(kind == SourceKind.HINDWI || kind == SourceKind.REKHTA) }
    override val name = if (kind == SourceKind.HINDWI) "Hindwi" else "Rekhta"
    private val base = if (kind == SourceKind.HINDWI) "https://www.hindwi.org" else "https://www.rekhta.org"

    override fun search(query: String): List<BookResult> {
        val reply = http.get("$base/search/ebooks?q=${TextUtil.enc(query)}", accept = "text/html").requireOk(name)
        return parse(reply.body, base, kind, name)
    }

    companion object {
        fun parse(html: String, base: String, kind: SourceKind, name: String): List<BookResult> {
            val doc = Jsoup.parse(html, base)
            if (doc.selectFirst(".searchEbookMatchCards") == null) {
                throw SourceUnavailableException("$name couldn't show its search results. Try its website.")
            }
            return doc.select(".searchEbookMatchCards .bookContent").mapNotNull { card ->
                val link = card.selectFirst("a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
                if (url.host != base.toHttpUrlOrNull()?.host ||
                    !(url.encodedPath.startsWith("/ebooks/detail/") || url.encodedPath.startsWith("/ebook-detail/"))) return@mapNotNull null
                val title = card.selectFirst(".bookTagline")?.text()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val author = card.selectFirst(".bookTitle")?.text().orEmpty()
                    .replace(Regex("^(Author|लेखक)\\s*:\\s*", RegexOption.IGNORE_CASE), "").trim()
                val cover = Regex("url\\(['\"]?([^)'\"]+)").find(link.attr("style"))?.groupValues?.get(1)
                    ?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()
                BookResult(
                    id = "${kind.name.lowercase()}:${url.encodedPath.substringAfterLast('/')}",
                    source = kind, sourceName = name, title = title, author = author,
                    year = TextUtil.yearOf(card.select(".bookRating, .bookTitle").text()),
                    // Rekhta contains translations and multiple scripts: don't infer language from the UI.
                    language = if (kind == SourceKind.HINDWI) "hi" else null,
                    coverUrl = cover, options = emptyList(), websiteUrl = url.toString(),
                    description = "Read on $name. The website may ask you to sign in or enter a verification code.",
                )
            }.distinctBy { it.id }.take(12)
        }
    }
}
