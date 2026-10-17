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
