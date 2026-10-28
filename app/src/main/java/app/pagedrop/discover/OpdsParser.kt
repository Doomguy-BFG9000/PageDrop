package app.pagedrop.discover

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.w3c.dom.Element
import org.w3c.dom.Node
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.ByteArrayInputStream
import java.net.URI
import javax.xml.parsers.DocumentBuilderFactory

internal data class OpdsLink(val rel: String?, val type: String?, val href: String, val length: Long?, val title: String?)

internal data class OpdsEntry(
    val id: String,
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val year: Int?,
    val language: String?,
    val links: List<OpdsLink>,
)

internal data class OpdsFeed(val title: String?, val baseUrl: String, val links: List<OpdsLink>, val entries: List<OpdsEntry>)

/** Minimal, forgiving OPDS 1.x (Atom) reader built on the platform DOM parser. */
internal object OpdsParser {

    private fun builder() = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        for ((feature, value) in listOf(
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        )) runCatching { setFeature(feature, value) }
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> org.xml.sax.InputSource(java.io.StringReader("")) }
    }

    private fun root(xml: String): Element {
        val trimmed = xml.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            throw SourceUnavailableException("This looks like an OPDS 2 (JSON) catalog. PageDrop reads OPDS 1.x (Atom) catalogs.")
        }
        return try {
            builder().parse(ByteArrayInputStream(trimmed.toByteArray(Charsets.UTF_8))).documentElement
        } catch (e: Exception) {
            throw SourceUnavailableException("That address doesn't return an OPDS catalog.")
        }
    }

    fun parseFeed(xml: String, baseUrl: String): OpdsFeed {
        val feed = root(xml)
        if (feed.localName != "feed") throw SourceUnavailableException("That address doesn't return an OPDS (Atom) catalog.")
        return OpdsFeed(
            title = feed.child("title")?.text(),
            baseUrl = baseUrl,
            links = feed.children("link").mapNotNull { link(it, baseUrl) },
            entries = feed.children("entry").mapNotNull { entry(it, baseUrl) },
        )
    }

    /** `<Url type="application/atom+xml" template="...{searchTerms}">` templates from an OpenSearch description. */
    fun parseOpenSearchTemplate(xml: String): String? {
        val doc = root(xml)
        val urls = doc.children("Url").mapNotNull { e ->
            e.getAttribute("template").takeIf { it.isNotBlank() }?.let { e.getAttribute("type") to it }
        }
        return (urls.firstOrNull { it.first.contains("atom+xml") && "profile=opds" in it.first }
            ?: urls.firstOrNull { it.first.contains("atom+xml") }
            ?: urls.firstOrNull { it.first.isBlank() && "searchTerms" in it.second })?.second
    }

    fun fillTemplate(template: String, query: String): String =
        Regex("\\{([^}]*)\\}").replace(template) { m ->
            val name = m.groupValues[1].removeSuffix("?").substringAfter(':')
            when (name) {
                "searchTerms" -> TextUtil.enc(query)
                "startPage", "startIndex" -> "1"
                else -> ""
            }
        }

    fun resolve(base: String, href: String): String =
        runCatching {
            // URI rejects raw braces, which OpenSearch templates contain.
            val safe = href.trim().replace("{", "%7B").replace("}", "%7D").replace(" ", "%20")
            URI(base).resolve(safe).toString().replace("%7B", "{").replace("%7D", "}")
        }.getOrDefault(href)

    private fun link(e: Element, base: String): OpdsLink? {
        val href = e.getAttribute("href").takeIf { it.isNotBlank() } ?: return null
        if (href.startsWith("data:", ignoreCase = true)) return null
        return OpdsLink(
            rel = e.getAttribute("rel").ifBlank { null },
            type = e.getAttribute("type").ifBlank { null },
            href = resolve(base, href),
            length = e.getAttribute("length").toLongOrNull(),
            title = e.getAttribute("title").ifBlank { null },
        )
    }

    private fun entry(e: Element, base: String): OpdsEntry? {
        val title = e.child("title")?.text()?.takeIf { it.isNotBlank() } ?: return null
        val id = e.child("id")?.text()?.takeIf { it.isNotBlank() } ?: title
        val summary = (e.child("summary") ?: e.child("content"))?.let { n ->
            val t = n.text()
            if (n.getAttribute("type").contains("html", ignoreCase = true)) TextUtil.stripTags(t) else t
        }?.takeIf { it.isNotBlank() }
        val date = (e.child("issued") ?: e.child("date") ?: e.child("published"))?.text()
        return OpdsEntry(
            id = id,
            title = title,
            authors = e.children("author").mapNotNull { it.child("name")?.text()?.takeIf { n -> n.isNotBlank() } },
            summary = summary,
            year = TextUtil.yearOf(date),
            language = e.child("language")?.text()?.let(TextUtil::shortLanguage),
            links = e.children("link").mapNotNull { link(it, base) },
        )
    }

    /** MIME type (or file extension) to our format; null for anything we can't use (e.g. CBZ, audio). */
    fun formatOf(type: String?, href: String): BookFormat? {
        val t = type?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val ext = href.substringBefore('?').substringAfterLast('.', "").lowercase()
        return when {
            t == "application/epub+zip" -> BookFormat.EPUB
            t == "application/pdf" -> BookFormat.PDF
            t == "application/x-mobi8-ebook" || t == "application/vnd.amazon.mobi8-ebook" -> BookFormat.AZW3
            t == "application/x-mobipocket-ebook" || t == "application/vnd.amazon.ebook" -> if (ext == "azw3") BookFormat.AZW3 else BookFormat.MOBI
            t == "text/plain" -> BookFormat.TXT
            t.isEmpty() || t == "application/octet-stream" -> when (ext) {
                "epub" -> BookFormat.EPUB; "pdf" -> BookFormat.PDF; "azw3" -> BookFormat.AZW3
                "mobi" -> BookFormat.MOBI; "txt" -> BookFormat.TXT; else -> null
            }
            else -> null
        }
    }

    private const val ACQ = "http://opds-spec.org/acquisition"

    /** Free acquisition only: plain/open-access links. Borrow, buy, subscribe, sample links are ignored. */
    fun acquisitionOptions(entry: OpdsEntry): List<DownloadOption> =
        entry.links.mapNotNull { l ->
            val rel = l.rel ?: return@mapNotNull null
            if (rel != ACQ && rel != "$ACQ/open-access") return@mapNotNull null
            val format = formatOf(l.type, l.href) ?: return@mapNotNull null
            DownloadOption(format, l.href, l.length, l.title ?: format.name)
        }.distinctBy { it.format }

    /**
     * Where a navigation feed's entries lead: links to other OPDS feeds from entries that offer no books themselves.
     * Entries that look like a book/title search come first, author searches last.
     */
    fun navigationLinks(feed: OpdsFeed): List<String> =
        feed.entries
            .filter { e -> e.links.none { it.rel?.startsWith(ACQ) == true } }
            .mapNotNull { e -> e.links.firstOrNull(::isFeedLink)?.let { e to it } }
            .sortedBy { (e, l) -> navRank("${e.title} ${l.title.orEmpty()}") }
            .map { it.second.href }
            .distinct()

    private val NOT_NAVIGATION = setOf("self", "start", "up", "search", "previous", "prev", "first", "last")

    private fun isFeedLink(l: OpdsLink): Boolean {
        val rel = l.rel.orEmpty()
        val type = l.type?.lowercase().orEmpty()
        if (rel in NOT_NAVIGATION || rel.startsWith(ACQ) || rel.startsWith("http://opds-spec.org/image")) return false
        if (rel == "subsection" || "profile=opds-catalog" in type) return true
        return type.startsWith("application/atom+xml") && "type=entry" !in type && (rel.isEmpty() || rel == "alternate")
    }

    private fun navRank(text: String): Int {
        val t = text.lowercase()
        return when {
            "author" in t -> 2
            "book" in t || "title" in t -> 0
            else -> 1
        }
    }

    fun coverOf(entry: OpdsEntry): String? =
        (entry.links.firstOrNull { it.rel == "http://opds-spec.org/image" }
            ?: entry.links.firstOrNull { it.rel == "http://opds-spec.org/image/thumbnail" })?.href

    private fun Element.children(local: String): List<Element> {
        val out = mutableListOf<Element>()
        var n: Node? = firstChild
        while (n != null) {
            if (n is Element && n.localName == local) out += n
            n = n.nextSibling
        }
        return out
    }

    private fun Element.child(local: String): Element? = children(local).firstOrNull()
    private fun Element.text(): String = textContent.orEmpty().trim()
}

internal class OpdsSource(
    private val catalog: OpdsCatalog,
    private val auth: BasicAuth?,
    private val http: DiscoverHttp,
    private val templateCache: MutableMap<String, String>,
) : BookSource {
    override val kind = SourceKind.OPDS
    override val name = catalog.title

    override fun search(query: String): List<BookResult> {
        val template = templateCache[catalog.id] ?: discoverTemplate().also { templateCache[catalog.id] = it }
        val feed = fetchFeed(OpdsParser.fillTemplate(template, query))
        val books = books(feed).ifEmpty { followNavigation(feed) }
        return books.distinctBy { it.id }.take(MAX_RESULTS).map { e ->
            BookResult(
                id = "opds:${catalog.id}:${e.id}",
                source = SourceKind.OPDS,
                sourceName = catalog.title,
                title = e.title,
                author = e.authors.joinToString(", "),
                year = e.year,
                language = e.language,
                coverUrl = OpdsParser.coverOf(e),
                description = e.summary,
                options = OpdsParser.acquisitionOptions(e),
            )
        }
    }

    /** Finds and caches the catalog's search address ahead of the first search. */
    fun prefetchTemplate() {
        if (templateCache[catalog.id] == null) templateCache[catalog.id] = discoverTemplate()
    }

    private fun books(feed: OpdsFeed) = feed.entries.filter { OpdsParser.acquisitionOptions(it).isNotEmpty() }

    /**
     * Some catalogs answer a search with a navigation feed ("Search books", "Search authors") instead of books.
     * Look one level down, in parallel; a failing link only matters if every link fails.
     */
    private fun followNavigation(feed: OpdsFeed): List<OpdsEntry> {
        val links = OpdsParser.navigationLinks(feed).take(MAX_NAVIGATION_LINKS)
        if (links.isEmpty()) return emptyList()
        val outcomes = runBlocking(Dispatchers.IO) {
            links.map { url -> async { runCatching { fetchFeed(url) } } }.awaitAll()
        }
        val feeds = outcomes.mapNotNull { it.getOrNull() }
        if (feeds.isEmpty()) throw outcomes.first().exceptionOrNull()!!
        return feeds.flatMap { mergeSameBook(books(it)) }
    }

    /**
     * One book's feed can list the same book more than once (Project Gutenberg: "no images" and "with images"
     * packagings under different ids). Show it once, with every download; the first listed wins per format.
     */
    private fun mergeSameBook(entries: List<OpdsEntry>): List<OpdsEntry> =
        entries.groupBy { e -> e.title.trim().lowercase() to e.authors.map { it.trim().lowercase() } }
            .values.map { same -> same.first().copy(links = same.flatMap { it.links }) }

    private fun fetchFeed(url: String): OpdsFeed {
        val reply = try {
            http.get(url, authFor(url), ATOM_ACCEPT)
        } catch (e: IllegalArgumentException) {
            throw SourceUnavailableException("$name gave a search address PageDrop can't open", cause = e)
        }.requireOk(name)
        return OpdsParser.parseFeed(reply.body, reply.finalUrl)
    }

    private val catalogHost = catalog.url.toHttpUrlOrNull()?.host

    /** Credentials only ever go to the catalog's own host. */
    private fun authFor(url: String): BasicAuth? = auth?.takeIf { url.toHttpUrlOrNull()?.host == catalogHost }

    private fun discoverTemplate(): String {
        val root = http.get(catalog.url, authFor(catalog.url), ATOM_ACCEPT).requireOk(name)
        val feed = OpdsParser.parseFeed(root.body, root.finalUrl)
        val search = feed.links.firstOrNull { it.rel == "search" && it.type?.contains("opensearchdescription") == true }
        if (search != null) {
            val desc = http.get(search.href, authFor(search.href), "application/opensearchdescription+xml, application/xml, */*").requireOk(name)
            OpdsParser.parseOpenSearchTemplate(desc.body)?.let { return OpdsParser.resolve(desc.finalUrl, it) }
        }
        feed.links.firstOrNull { it.rel == "search" && it.type?.contains("atom+xml") == true && "{searchTerms}" in it.href }
            ?.let { return it.href }
        throw SourceUnavailableException("This catalog doesn't offer search")
    }
    private companion object {
        const val ATOM_ACCEPT = "application/atom+xml, application/xml;q=0.9, */*;q=0.5"
        const val MAX_RESULTS = 25
        const val MAX_NAVIGATION_LINKS = 5
    }
}
