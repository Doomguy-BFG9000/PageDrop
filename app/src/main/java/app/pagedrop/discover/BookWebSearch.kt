package app.pagedrop.discover

/** Catalogs whose interactive search/download flow needs a browser. These are shortcuts, not book results. */
object BookWebSearch {
    fun links(query: String, failedSources: Set<SourceKind> = emptySet()): List<BookSearchLink> {
        val q = TextUtil.enc(TextUtil.cleanQuery(query))
        if (q.isEmpty()) return emptyList()
        val fallbacks = failedSources.mapNotNull { kind ->
            when (kind) {
                SourceKind.GUTENBERG -> BookSearchLink("Project Gutenberg", "https://www.gutenberg.org/ebooks/search/?query=$q", "Search on the website while the catalog is unavailable")
                SourceKind.STANDARD_EBOOKS -> BookSearchLink("Standard Ebooks", "https://standardebooks.org/ebooks?query=$q", "Search on the website while the catalog is unavailable")
                SourceKind.INTERNET_ARCHIVE -> BookSearchLink("Internet Archive", "https://archive.org/search?query=$q", "Search on the website while the catalog is unavailable")
                SourceKind.DIGITAL_LIBRARY_INDIA -> BookSearchLink("Digital Library of India", "https://archive.org/search?query=${TextUtil.enc("collection:digitallibraryindia ($query)")}", "Search the Indian collection on Internet Archive")
                SourceKind.OPEN_LIBRARY -> BookSearchLink("Open Library", "https://openlibrary.org/search?q=$q", "Read or borrow on the website")
                SourceKind.HINDI_WIKISOURCE -> BookSearchLink("Hindi Wikisource", "https://hi.wikisource.org/w/index.php?title=Special:Search&search=$q", "Search Hindi texts on the website")
                SourceKind.HINDWI -> BookSearchLink("Hindwi", "https://www.hindwi.org/search/ebooks?q=$q", "Search Hindi literature on the website")
                SourceKind.REKHTA -> BookSearchLink("Rekhta", "https://www.rekhta.org/search/ebooks?q=$q", "Search Urdu literature on the website")
                SourceKind.ENGLISH_WIKISOURCE -> BookSearchLink("English Wikisource", "https://en.wikisource.org/w/index.php?title=Special:Search&search=$q", "Search English texts on the website")
                SourceKind.FADED_PAGE -> BookSearchLink("Faded Page", "https://www.fadedpage.com/csearch.php?title=$q", "Search English titles on Faded Page")
                SourceKind.MANYBOOKS -> BookSearchLink("ManyBooks", "https://manybooks.net/search-book?search=$q", "Search ebooks on ManyBooks; an account may be required")
                SourceKind.OPDS, SourceKind.WEBSITE -> null
            }
        }
        return fallbacks + listOf(
            BookSearchLink("ePustakalay", "https://epustakalay.com/search_gcse/?q=$q", "Hindi and Indian-language books; search on the website"),
            BookSearchLink("Anna’s Archive", "https://annas-archive.gl/search?q=$q", "Shadow-library search; availability and download access vary"),
            BookSearchLink("Global Grey", "https://www.google.com/search?q=${TextUtil.enc("site:globalgreyebooks.com ${TextUtil.cleanQuery(query)}")}", "Search Global Grey’s English classics through its Google site search"),
            BookSearchLink("OceanofPDF", "https://oceanofpdf.com/", "Search for “${TextUtil.cleanQuery(query)}” on the website; automated access is blocked"),
        )
    }
}
