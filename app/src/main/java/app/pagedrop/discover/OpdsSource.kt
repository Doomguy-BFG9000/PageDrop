package app.pagedrop.discover

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Talks to one user-added OPDS catalog: fetches feeds, finds its search address, turns feeds into
 * [CatalogPage]s. Used by both global search ([OpdsSource]) and the catalog view. Blocking; never on Main.
 * Credentials only ever go to the catalog's own host, whatever address a feed hands out.
 */
internal class OpdsClient(
    val catalog: OpdsCatalog,
    private val auth: BasicAuth?,
    private val http: DiscoverHttp,
    private val templateCache: MutableMap<String, String>,
) {
    val name: String get() = catalog.title

    private val catalogHost = catalog.url.toHttpUrlOrNull()?.host

    private fun authFor(url: String): BasicAuth? = auth?.takeIf { url.toHttpUrlOrNull()?.host == catalogHost }

    fun fetchFeed(url: String): OpdsFeed {
        val reply = try {
            http.get(url, authFor(url), ATOM_ACCEPT)
        } catch (e: IllegalArgumentException) {
            throw SourceUnavailableException("$name gave an address PageDrop can't open", cause = e)
        }.requireOk(name)
        return OpdsParser.parseFeed(reply.body, reply.finalUrl)
    }

    /** One page of a feed: entries in feed order, the next-page link and the feed's search link. */
    fun page(url: String): CatalogPage = toPage(fetchFeed(url))

    /** Search from the catalog view: [link] is the current feed's search link, or null for the catalog's own. */
    fun search(link: CatalogSearch?, query: String): CatalogPage {
        val template = when {
            link == null -> rootTemplate()
            link.isDescription -> templateCache.getOrPut("${catalog.id}|${link.href}") { templateFromDescription(link.href) }
            else -> link.href
        }
        return page(OpdsParser.fillTemplate(template, query))
    }

    /** The catalog's search template, found once from its root feed (and cached, see [OpdsSource.prefetchTemplate]). */
    fun rootTemplate(): String = templateCache[catalog.id] ?: discoverTemplate().also { templateCache[catalog.id] = it }

    fun discoverTemplate(): String {
        val root = http.get(catalog.url, authFor(catalog.url), ATOM_ACCEPT).requireOk(name)
        val feed = OpdsParser.parseFeed(root.body, root.finalUrl)
        val search = OpdsParser.searchOf(feed)
        if (search != null) {
            if (search.isDescription) return templateFromDescription(search.href)
            return search.href
        }
        throw SourceUnavailableException("This catalog doesn't offer search")
    }

    private fun templateFromDescription(href: String): String {
        val desc = http.get(href, authFor(href), "application/opensearchdescription+xml, application/xml, */*").requireOk(name)
        return OpdsParser.parseOpenSearchTemplate(desc.body)?.let { OpdsParser.resolve(desc.finalUrl, it) }
            ?: throw SourceUnavailableException("This catalog doesn't offer search")
    }

    fun toPage(feed: OpdsFeed): CatalogPage {
        val items = OpdsParser.mergeSameBook(OpdsParser.items(feed)).map { item ->
            when (item) {
                is OpdsItem.Book -> CatalogItem.Book(toResult(item.entry))
                is OpdsItem.Folder -> CatalogItem.Folder(
                    id = item.entry.id,
                    title = item.entry.title,
                    summary = item.entry.summary ?: item.entry.authors.joinToString(", ").ifBlank { null },
                    thumbnailUrl = OpdsParser.thumbnailOf(item.entry),
                    url = item.url,
                )
            }
        }
        val folders = items.any { it is CatalogItem.Folder }
        val books = items.any { it is CatalogItem.Book }
        return CatalogPage(
            title = feed.title?.takeIf { it.isNotBlank() },
            url = feed.baseUrl,
            items = items,
            nextUrl = OpdsParser.nextUrl(feed),
            search = OpdsParser.searchOf(feed),
            kind = when {
                folders && books -> CatalogPageKind.MIXED
                folders -> CatalogPageKind.NAVIGATION
                books -> CatalogPageKind.ACQUISITION
                else -> CatalogPageKind.EMPTY
            },
        )
    }

    fun toResult(e: OpdsEntry) = BookResult(
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

    companion object {
        const val ATOM_ACCEPT = "application/atom+xml, application/xml;q=0.9, */*;q=0.5"
    }
}

/** A catalog's answer to a global search: books, or (when it answered with a menu) the menu to choose from. */
internal class OpdsSearchOutcome(val results: List<BookResult>, val menu: CatalogMenu?)

internal class OpdsSource(
    catalog: OpdsCatalog,
    auth: BasicAuth?,
    http: DiscoverHttp,
    templateCache: MutableMap<String, String>,
) : BookSource {
    private val client = OpdsClient(catalog, auth, http, templateCache)
    override val kind = SourceKind.OPDS
    override val name = catalog.title

    override fun search(query: String): List<BookResult> = searchCatalog(query).results

    /**
     * Books when the catalog answers with books. When it answers with a menu ("Search books", "Search authors")
     * nothing is followed: the menu is handed back so the user chooses where to look.
     */
    fun searchCatalog(query: String): OpdsSearchOutcome {
        val feed = client.fetchFeed(OpdsParser.fillTemplate(client.rootTemplate(), query))
        val items = OpdsParser.items(feed)
        val books = items.filterIsInstance<OpdsItem.Book>().map { it.entry }.filter { OpdsParser.acquisitionOptions(it).isNotEmpty() }
        if (books.isNotEmpty()) {
            val results = books.distinctBy { it.id }.take(MAX_RESULTS).map(client::toResult)
            return OpdsSearchOutcome(results, null)
        }
        val folders = items.filterIsInstance<OpdsItem.Folder>()
        if (folders.isEmpty()) return OpdsSearchOutcome(emptyList(), null)
        return OpdsSearchOutcome(
            emptyList(),
            CatalogMenu(
                catalogId = client.catalog.id,
                catalogName = name,
                feedUrl = feed.baseUrl,
                optionCount = folders.size,
                optionTitles = folders.map { it.entry.title },
            ),
        )
    }

    /** Finds and caches the catalog's search address ahead of the first search. */
    fun prefetchTemplate() {
        client.rootTemplate()
    }

    private companion object {
        const val MAX_RESULTS = 25
    }
}
