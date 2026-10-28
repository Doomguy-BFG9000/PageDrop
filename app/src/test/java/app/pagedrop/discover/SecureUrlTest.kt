package app.pagedrop.discover

import org.junit.Assert.assertEquals
import org.junit.Test

class SecureUrlTest {
    @Test
    fun upgradesPlainHttp() {
        assertEquals(
            "https://m.gutenberg.org/ebooks/search.opds/?query=frankenstein",
            secureUrl("http://m.gutenberg.org/ebooks/search.opds/?query=frankenstein"),
        )
        assertEquals("https://Example.org/a", secureUrl("HTTP://Example.org/a"))
    }

    @Test
    fun leavesEverythingElseAlone() {
        assertEquals("https://www.gutenberg.org/ebooks.opds/", secureUrl("https://www.gutenberg.org/ebooks.opds/"))
        assertEquals("ftp://host/x", secureUrl("ftp://host/x"))
        assertEquals("httpx://host", secureUrl("httpx://host"))
        assertEquals("", secureUrl(""))
    }

    @Test
    fun gutenbergTemplateFillsToAnHttpsSearch() {
        val template = "http://m.gutenberg.org/ebooks/search.opds/?query={searchTerms}"
        assertEquals(
            "https://m.gutenberg.org/ebooks/search.opds/?query=frankenstein",
            secureUrl(OpdsParser.fillTemplate(template, "frankenstein")),
        )
    }
}
