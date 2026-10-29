package app.pagedrop.discover

import org.w3c.dom.Element
import org.w3c.dom.Node
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

internal sealed interface OpdsItem {
    data class Folder(val entry: OpdsEntry, val url: String) : OpdsItem
    data class Book(val entry: OpdsEntry) : OpdsItem
}

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

    /** The next page of a paginated feed (`rel="next"`), if any. */
    fun nextUrl(feed: OpdsFeed): String? = feed.links.firstOrNull { it.rel == "next" }?.href

    /** The feed's own search link: an OpenSearch description, else an Atom link that is itself a template. */
    fun searchOf(feed: OpdsFeed): CatalogSearch? {
        val searches = feed.links.filter { it.rel == "search" }
        searches.firstOrNull { it.type?.contains("opensearchdescription") == true }
            ?.let { return CatalogSearch(it.href, true) }
        return searches.firstOrNull { it.type?.contains("atom+xml") == true && "{searchTerms}" in it.href }
            ?.let { CatalogSearch(it.href, false) }
    }

    /**
     * What each entry is, in feed order: a book when it carries acquisition links (even ones we can't use),
     * otherwise a folder when it links to another feed. Entries that are neither are skipped.
     */
    fun items(feed: OpdsFeed): List<OpdsItem> = feed.entries.mapNotNull { e ->
        if (e.links.any { it.rel?.startsWith(ACQ) == true }) OpdsItem.Book(e)
        else e.links.firstOrNull(::isFeedLink)?.let { OpdsItem.Folder(e, it.href) }
    }

    /**
     * Some catalogs list one book in several packagings (for example "with images" and "no images") as separate
     * entries in that book's own feed. When every book on the page has the same title and authors, that is what
     * this is: show it once, with every download; the first listed wins per format. On any other page (several
     * different books, even if two share a title) nothing is merged, so each edition keeps its own files.
     */
    fun mergeSameBook(items: List<OpdsItem>): List<OpdsItem> {
        val books = items.filterIsInstance<OpdsItem.Book>()
        if (books.size < 2) return items
        fun key(e: OpdsEntry) = e.title.trim().lowercase() to e.authors.map { it.trim().lowercase() }
        if (books.map { key(it.entry) }.distinct().size != 1) return items
        val first = books.first().entry
        val merged = OpdsItem.Book(first.copy(links = books.flatMap { it.entry.links }))
        return items.mapNotNull { if (it !is OpdsItem.Book) it else if (it === books.first()) merged else null }
    }

    fun thumbnailOf(entry: OpdsEntry): String? =
        (entry.links.firstOrNull { it.rel == "http://opds-spec.org/image/thumbnail" }
            ?: entry.links.firstOrNull { it.rel == "http://opds-spec.org/image" })?.href

    private val NOT_NAVIGATION =setOf("self", "start", "up", "search", "previous", "prev", "first", "last")

    private fun isFeedLink(l: OpdsLink): Boolean {
        val rel = l.rel.orEmpty()
        val type = l.type?.lowercase().orEmpty()
        if (rel in NOT_NAVIGATION || rel.startsWith(ACQ) || rel.startsWith("http://opds-spec.org/image")) return false
        if (rel == "subsection" || "profile=opds-catalog" in type) return true
        return type.startsWith("application/atom+xml") && "type=entry" !in type && (rel.isEmpty() || rel == "alternate")
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
