package app.pagedrop.metadata

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class BookMetadataSearcherTest {
    private val olJson = """{"numFound":2,"docs":[
        {"title":"The Great Gatsby","author_name":["F. Scott Fitzgerald"],"isbn":["0743273567","9780743273565"],"cover_i":8410459,"publisher":["Scribner"],"first_publish_year":1925},
        {"title":"Gatsby Girl","author_name":["Somebody"]}]}"""
    private val googleJson = """{"items":[{"volumeInfo":{"title":"The Great Gatsby","authors":["F. Scott Fitzgerald"],
        "publisher":"Simon","publishedDate":"2004-09-30","industryIdentifiers":[{"type":"ISBN_10","identifier":"0743273567"}],
        "imageLinks":{"thumbnail":"http://books.google.com/books/content?id=X&printsec=frontcover&img=1&zoom=1&edge=curl&source=gbs_api"}}}]}"""

    private fun http(ol: () -> MetadataHttp.Reply, google: () -> MetadataHttp.Reply, urls: MutableList<String>? = null) =
        MetadataHttp { url ->
            urls?.add(url)
            if ("openlibrary.org" in url) ol() else google()
        }

    private fun ok(body: String) = MetadataHttp.Reply(200, body)

    @Test fun mergesBothSourcesAndPrefersOpenLibraryCover() = runTest {
        val r = BookMetadataSearcher(http({ ok(olJson) }, { ok(googleJson) })).lookup("The Great Gatsby", "F. Scott Fitzgerald")
        r as MetadataLookupResult.Matches
        val first = r.candidates.first()
        assertEquals("The Great Gatsby", first.title)
        assertEquals(BookMetadataCandidate.Source.MERGED, first.source)
        assertEquals("https://covers.openlibrary.org/b/id/8410459-L.jpg?default=false", first.coverUrl)
        assertEquals("9780743273565", first.isbn)
        assertEquals(2, r.candidates.size) // merged Gatsby + Gatsby Girl
        assertNotNull(r.best)
        assertEquals(false, r.degraded)
    }

    @Test fun googleRateLimitDoesNotHideOpenLibraryResults() = runTest {
        val r = BookMetadataSearcher(http({ ok(olJson) }, { MetadataHttp.Reply(429, "quota") })).lookup("The Great Gatsby", "")
        r as MetadataLookupResult.Matches
        assertTrue(r.degraded)
        assertEquals("The Great Gatsby", r.best?.title)
    }

    @Test fun noMatchesWhenOpenLibraryAnswersEmptyEvenIfGoogleIsDown() = runTest {
        val r = BookMetadataSearcher(http({ ok("""{"docs":[]}""") }, { MetadataHttp.Reply(429, "quota") })).lookup("zzqx nothing", "")
        assertEquals(MetadataLookupResult.NoMatches, r)
    }

    @Test fun offlineIsUnavailableNotNoMatches() = runTest {
        val boom = { throw UnknownHostException("no network") }
        val r = BookMetadataSearcher(http(boom, boom)).lookup("The Great Gatsby", "")
        r as MetadataLookupResult.Unavailable
        assertTrue(r.offline)
    }

    @Test fun serverErrorsAreUnavailable() = runTest {
        val err = { MetadataHttp.Reply(503, "x") }
        val r = BookMetadataSearcher(http(err, err)).lookup("The Great Gatsby", "")
        r as MetadataLookupResult.Unavailable
        assertEquals(false, r.offline)
    }

    @Test fun garbageJsonIsUnavailable() = runTest {
        val r = BookMetadataSearcher(http({ ok("<html>") }, { ok("<html>") })).lookup("The Great Gatsby", "")
        assertTrue(r is MetadataLookupResult.Unavailable)
    }

    @Test fun unsearchableQueryMakesNoRequests() = runTest {
        val urls = mutableListOf<String>()
        val r = BookMetadataSearcher(http({ ok(olJson) }, { ok(googleJson) }, urls)).lookup("OD4388757722123", "")
        assertEquals(MetadataLookupResult.NoMatches, r)
        assertTrue(urls.isEmpty())
    }

    @Test fun filenameQueryIsCleaned() = runTest {
        val urls = mutableListOf<String>()
        BookMetadataSearcher(http({ ok(olJson) }, { ok(googleJson) }, urls)).lookup("the-great-gatsby", "Unknown Author")
        val ol = urls.first { "openlibrary" in it }
        assertTrue(ol, "q=the%20great%20gatsby" in ol)
        assertTrue(ol, "fields=title%2Cauthor_name%2Cisbn%2Ccover_i%2Cpublisher%2Cfirst_publish_year" in ol)
    }

    @Test fun isbnSearchUsesIsbnParameters() = runTest {
        val urls = mutableListOf<String>()
        BookMetadataSearcher(http({ ok(olJson) }, { ok(googleJson) }, urls)).lookup("9780743273565", "")
        assertTrue(urls.first { "openlibrary" in it }.contains("isbn=9780743273565"))
        assertTrue(urls.first { "googleapis" in it }.contains("isbn%3A9780743273565"))
    }

    @Test fun retriesTitleOnlyWhenAuthorNarrowsToNothing() = runTest {
        val urls = mutableListOf<String>()
        var calls = 0
        val h = MetadataHttp { url ->
            urls.add(url)
            if ("openlibrary" in url) { if (calls++ == 0) ok("""{"docs":[]}""") else ok(olJson) } else ok("""{"totalItems":0}""")
        }
        val r = BookMetadataSearcher(h).lookup("The Great Gatsby", "Wrong Person")
        assertTrue(r is MetadataLookupResult.Matches)
        assertEquals(2, urls.count { "openlibrary" in it })
    }

    @Test fun googleCoverIsUpgraded() {
        val c = BookMetadataSearcher(MetadataHttp { throw IOException() }).parseGoogleBooks(googleJson).single()
        assertEquals("https://books.google.com/books/content?id=X&printsec=frontcover&img=1&zoom=0&source=gbs_api", c.coverUrl)
        assertEquals(2004, c.year)
    }
}
