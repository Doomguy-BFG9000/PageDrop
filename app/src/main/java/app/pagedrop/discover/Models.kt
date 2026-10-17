package app.pagedrop.discover

/** Book file formats PageDrop understands, in no particular order (see [BookSearchEngine.bestOption] for preference). */
enum class BookFormat(val extension: String) {
    AZW3("azw3"), MOBI("mobi"), EPUB("epub"), PDF("pdf"), TXT("txt");

    /** True for formats a stock Kindle opens without conversion. */
    val kindleNative: Boolean get() = this == AZW3 || this == MOBI
}

enum class SourceKind { GUTENBERG, STANDARD_EBOOKS, INTERNET_ARCHIVE, OPDS }

data class DownloadOption(
    val format: BookFormat,
    val url: String,
    val sizeBytes: Long? = null,
    val label: String? = null,
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
    /** May be empty for Internet Archive until [BookSearchEngine.resolve] runs. */
    val options: List<DownloadOption>,
    /** True means call [BookSearchEngine.resolve] to fill [options]. */
    val needsResolve: Boolean = false,
)

data class SourceError(val sourceName: String, val offline: Boolean, val message: String)

data class SearchResults(val query: String, val results: List<BookResult>, val errors: List<SourceError>)

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
