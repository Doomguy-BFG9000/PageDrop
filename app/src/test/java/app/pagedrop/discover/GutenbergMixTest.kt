package app.pagedrop.discover

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergMixTest {
    @Test
    fun differentBooksFromSeveralSourcesAreAllKept() = runBlocking {
        val http = FakeHttp().apply {
            on("gutendex.com", fixture("gutendex_pride_and_prejudice.json"))
            on("standardebooks.org/ebooks?query", fixture("standardebooks_search_empty.html"))
            on("archive.org/advancedsearch", fixture("ia_search_frankenstein.json"))
        }
        val res = BookSearchEngine(newSettings(), http, 5_000).search("pride and prejudice")
        assertEquals("Pride and Prejudice", res.results.first().title)
        assertEquals(SourceKind.GUTENBERG, res.results.first().source)
        assertTrue(res.results.first().options.any { it.format == BookFormat.AZW3 })
        assertTrue(res.results.size > 1)
    }
}
