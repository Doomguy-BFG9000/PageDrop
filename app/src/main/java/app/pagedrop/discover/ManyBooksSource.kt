package app.pagedrop.discover

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

/** Search results are public; downloads can require an account and stay on the source website. */
internal class ManyBooksSource(private val http: DiscoverHttp) : BookSource {
    override val kind = SourceKind.MANYBOOKS
    override val name = "ManyBooks"

    override fun search(query: String): List<BookResult> = parse(
        http.get("https://manybooks.net/search-book?search=${TextUtil.enc(query)}", accept = "text/html").requireOk(name).body,
    )

    companion object {
        fun parse(html: String): List<BookResult> {
            val doc = Jsoup.parse(html, "https://manybooks.net")
            val view = doc.selectFirst(".view-id-search_results_index")
                ?: throw SourceUnavailableException("ManyBooks couldn't show its search results. Try its website.")
            // The empty state includes popular books. Those are recommendations, not matches for this query.
            if (view.selectFirst(".view-empty") != null) return emptyList()
            return view.select(".view-content article.book").mapNotNull { card ->
                val link = card.selectFirst(".field--name-field-title a[href]") ?: return@mapNotNull null
                val url = link.absUrl("href").toHttpUrlOrNull() ?: return@mapNotNull null
                if (!url.isHttps || url.host != "manybooks.net" || !url.encodedPath.startsWith("/titles/")) return@mapNotNull null
                val title = link.text().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                BookResult(
                    id = "manybooks:${url.encodedPath.removePrefix("/titles/")}", source = SourceKind.MANYBOOKS, sourceName = "ManyBooks",
                    title = title, author = card.select(".field--name-field-author-er .field--item").joinToString(", ") { it.text() },
                    coverUrl = card.selectFirst("img[src]")?.absUrl("src")?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString(),
                    description = "Read or download on ManyBooks. A free account may be required.",
                    options = emptyList(), websiteUrl = url.toString(),
                )
            }.distinctBy { it.id }.take(12)
        }
    }
}
