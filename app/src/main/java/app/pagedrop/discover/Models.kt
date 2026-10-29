package app.pagedrop.discover

/** Book file formats PageDrop understands, in no particular order (see [BookSearchEngine.bestOption] for preference). */
enum class BookFormat(val extension: String) {
    AZW3("azw3"), MOBI("mobi"), EPUB("epub"), PDF("pdf"), TXT("txt");

    /** True for formats a stock Kindle opens without conversion. */
    val kindleNative: Boolean get() = this == AZW3 || this == MOBI
}

enum class SourceKind { GUTENBERG, STANDARD_EBOOKS, INTERNET_ARCHIVE, DIGITAL_LIBRARY_INDIA, OPEN_LIBRARY, HINDI_WIKISOURCE, HINDWI, REKHTA, ENGLISH_WIKISOURCE, FADED_PAGE, MANYBOOKS, OPDS, WEBSITE;
    val isBuiltIn: Boolean get() = this != OPDS && this != WEBSITE
}

data class DownloadOption(
    val format: BookFormat,
    val url: String,
    val sizeBytes: Long? = null,
    val label: String? = null,
    /** Transient browser context; never persisted with library books. */
    val browserSession: BrowserDownloadSession? = null,
)

data class BookResult(
    /** Stable: "<sourceKind lowercase>:<id>", e.g. "gutenberg:84", "standard_ebooks:mary-shelley/frankenstein", "internet_archive:<identifier>"; OPDS: "opds:<catalogId>:<entryId>". */
    val id: String,
    val source: SourceKind,
    val sourceName: String,
    val title: String,
    /** "" if unknown. */
    val author: String,
    val year: Int? = null,
    val language: String? = null,
    val coverUrl: String? = null,
    val description: String? = null,
    /** Empty for web-reader results, or until [BookSearchEngine.resolve] checks an Archive edition. */
    val options: List<DownloadOption>,
    /** True means call [BookSearchEngine.resolve] to fill [options]. */
    val needsResolve: Boolean = false,
    /** Reader/details page, also available when a download is blocked or requires borrowing. */
    val websiteUrl: String? = null,
    /** Exact Archive edition to check for downloadable files; never a guessed filename. */
    val archiveIdentifier: String? = null,
)

data class SourceError(val sourceName: String, val offline: Boolean, val message: String)

data class BookSearchLink(val sourceName: String, val url: String, val description: String)

data class SearchResults(
    val query: String,
    val results: List<BookResult>,
    val errors: List<SourceError>,
    val websiteSearches: List<BookSearchLink> = emptyList(),
    /** Catalogs that answered with a menu instead of books; the user picks where to look. */
    val catalogMenus: List<CatalogMenu> = emptyList(),
)

/** A catalog's answer to a search was a menu ("Search books", "Search authors"): [feedUrl] is that menu. */
data class CatalogMenu(
    val catalogId: String,
    val catalogName: String,
    val feedUrl: String,
    val optionCount: Int,
    val optionTitles: List<String>,
)

/** Where a catalog (or one of its feeds) can be searched: an OpenSearch description or a ready-made template. */
data class CatalogSearch(val href: String, val isDescription: Boolean)

enum class CatalogPageKind { NAVIGATION, ACQUISITION, MIXED, EMPTY }

sealed interface CatalogItem {
    /** A way further into the catalog; opens [url] as a feed. */
    data class Folder(
        val id: String,
        val title: String,
        val summary: String?,
        val thumbnailUrl: String?,
        val url: String,
    ) : CatalogItem

    /** A book; [result] opens in the usual result sheet. */
    data class Book(val result: BookResult) : CatalogItem
}

/** One page of one OPDS feed, in feed order. */
data class CatalogPage(
    val title: String?,
    val url: String,
    val items: List<CatalogItem>,
    val nextUrl: String?,
    val search: CatalogSearch?,
    val kind: CatalogPageKind,
)

data class OpdsCatalog(
    val id: String,
    val title: String,
    val url: String,
    val username: String? = null,
    val enabled: Boolean = true,
)

/** A source failed in a way the UI can describe in plain words. */
class SourceUnavailableException(message: String, val offline: Boolean = false, cause: Throwable? = null) :
    java.io.IOException(message, cause)

internal interface BookSource {
    val kind: SourceKind
    val name: String

    /** Blocking; always called off the main thread. Throws [java.io.IOException] on failure. */
    fun search(query: String): List<BookResult>
}
