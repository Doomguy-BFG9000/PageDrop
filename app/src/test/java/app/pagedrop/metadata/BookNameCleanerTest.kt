package app.pagedrop.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookNameCleanerTest {
    @Test fun dashedLowercaseName() {
        val p = BookNameCleaner.parse("the-great-gatsby.pdf")
        assertEquals("the great gatsby", p.title)
        assertTrue(p.looksLikeFilename)
        assertNull(p.author)
    }

    @Test fun titleDashAuthor() {
        val p = BookNameCleaner.parse("Tender is the Flesh - Agustina Bazterrica.epub")
        assertEquals("Tender is the Flesh", p.title)
        assertEquals("Agustina Bazterrica", p.author)
        assertTrue(p.orderAmbiguous)
        assertFalse(p.looksLikeFilename)
    }

    @Test fun underscoreAndIdToken() {
        val p = BookNameCleaner.parse("Ticket_TK1010718726t83")
        assertEquals("Ticket", p.title)
        assertTrue(p.looksLikeFilename)
        assertTrue(p.lossy)
    }

    @Test fun runTogetherLowercase() {
        val p = BookNameCleaner.parse("flowersforalgernon")
        assertEquals("flowers for algernon", p.title)
        assertTrue(p.looksLikeFilename)
    }

    @Test fun mostlyDigitsHasNoQuery() {
        val p = BookNameCleaner.parse("OD4388757722123")
        assertEquals("", p.title)
        assertTrue(p.looksLikeFilename)
        assertFalse(p.isSearchable)
    }

    @Test fun isbnIsExtracted() {
        assertEquals("9780743273565", BookNameCleaner.extractIsbn("9780743273565"))
        assertEquals("9780743273565", BookNameCleaner.extractIsbn("978-0-7432-7356-5"))
        assertEquals("043942089X", BookNameCleaner.extractIsbn("Book 043942089X.pdf"))
        assertNull(BookNameCleaner.extractIsbn("OD4388757722123"))
        assertNull(BookNameCleaner.extractIsbn("The Great Gatsby"))
    }

    @Test fun realTitlesAreNotFilenames() {
        assertFalse(BookNameCleaner.looksLikeFilename("The Great Gatsby"))
        assertFalse(BookNameCleaner.looksLikeFilename("1984"))
        assertFalse(BookNameCleaner.looksLikeFilename("Dune"))
        assertFalse(BookNameCleaner.looksLikeFilename("Fahrenheit 451"))
        assertTrue(BookNameCleaner.looksLikeFilename("the_great_gatsby"))
        assertTrue(BookNameCleaner.looksLikeFilename(""))
    }

    @Test fun missingMetadataSignals() {
        assertTrue(BookNameCleaner.isMissingMetadata("The Great Gatsby", "Unknown Author"))
        assertTrue(BookNameCleaner.isMissingMetadata("The Great Gatsby", ""))
        assertTrue(BookNameCleaner.isMissingMetadata("the-great-gatsby", "F. Scott Fitzgerald"))
        assertTrue(BookNameCleaner.isMissingMetadata("Tender is the Flesh - Agustina Bazterrica", "Unknown Author"))
        assertFalse(BookNameCleaner.isMissingMetadata("The Great Gatsby", "F. Scott Fitzgerald"))
    }

    @Test fun stripsDownloadNoise() {
        val p = BookNameCleaner.parse("Dune (z-lib.org).epub")
        assertEquals("Dune", p.title)
    }

    @Test fun camelCaseIsSplit() {
        assertEquals("Brave New World", BookNameCleaner.parse("BraveNewWorld.pdf").title)
    }

    @Test fun fileNameForIsSafe() {
        assertEquals("Tender Flesh - A B.mobi", BookFileMetadataEmbedder.fileNameFor("Tender: Flesh", "A B", "MOBI"))
        assertEquals("Book.pdf", BookFileMetadataEmbedder.fileNameFor("", "Unknown Author", "pdf"))
        assertEquals("What if - X.azw3", BookFileMetadataEmbedder.fileNameFor("What if?", "X", "azw3"))
    }
}
