package app.pagedrop.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameCleaningTest {
    private fun check(raw: String, title: String, author: String?) {
        val p = BookNameCleaner.parse(raw)
        assertEquals("title of $raw", title, p.title)
        assertEquals("author of $raw", author, p.author)
    }

    @Test fun realWorldNames() {
        check("greatgatsby_ebook_free.pdf", "greatgatsby", null)
        check("1984-george-orwell-z-lib.org.epub", "1984 george orwell", null)
        check("Dune (Frank Herbert) (z-library.sk, 1lib.sk, z-lib.sk).epub", "Dune", "Frank Herbert")
        check("oceanofpdf.com_The_Alchemist.pdf", "The Alchemist", null)
        check("harry-potter-1-pdf-free-download.pdf", "harry potter 1", null)
        check("Atomic Habits by James Clear [PDF].pdf", "Atomic Habits", "James Clear")
        check("Sapiens (retail).epub", "Sapiens", null)
        check("The Hobbit v2 (2012).epub", "The Hobbit", null)
        check("www.somesite.com - Dune.pdf", "Dune", null)
    }

    @Test fun asinHasNoQuery() {
        val p = BookNameCleaner.parse("B07D23CFGR.azw3")
        assertEquals("", p.title)
        assertTrue(p.looksLikeFilename)
    }

    @Test fun freeIsKeptInRealTitles() {
        check("Free Will.epub", "Free Will", null)
    }

    @Test fun extraQuerySplitsConcatenatedWords() {
        assertEquals(listOf("great gatsby"), BookNameCleaner.extraQueries("greatgatsby_ebook_free.pdf").map { it.replace("  ", " ") }.take(1))
        assertTrue(BookNameCleaner.extraQueries("The Alchemist.pdf").isEmpty())
    }

    @Test fun noiseDetectionMarksFilenames() {
        assertTrue(BookNameCleaner.looksLikeFilename("Dune (z-library.sk)"))
        assertTrue(BookNameCleaner.looksLikeFilename("B07D23CFGR"))
        assertFalse(BookNameCleaner.looksLikeFilename("The Great Gatsby"))
        assertFalse(BookNameCleaner.looksLikeFilename("1984"))
    }

    @Test fun junkTitlesAndAuthors() {
        val badTitles = listOf("", "  ", "Unknown", "Untitled", "Microsoft Word - ch1.doc", "chapter1.docx", "Adobe InDesign CS6",
            "www.free-ebooks.com", "greatgatsby_ebook_free", "B07D23CFGR", "Download free eBooks")
        badTitles.forEach { assertFalse("'$it'", MetadataJunk.isUsableTitle(it)) }
        listOf("The Great Gatsby", "1984", "THE GREAT GATSBY", "Dune").forEach { assertTrue(it, MetadataJunk.isUsableTitle(it)) }
        listOf("", "Unknown", "admin", "Windows User", "calibre", "z-lib.org").forEach { assertFalse(it, MetadataJunk.isUsableAuthor(it)) }
        assertTrue(MetadataJunk.isUsableAuthor("F. Scott Fitzgerald"))
    }
}
