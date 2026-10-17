package app.pagedrop.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PdfFirstPageGuessTest {
    @Test fun titleBeforeByLine() {
        val g = PdfFirstPageGuess.guessFromText("The Great Gatsby\nBy F. Scott Fitzgerald\n\nDownload free eBooks at www.planetpdf.com")!!
        assertEquals("The Great Gatsby", g.title)
        assertEquals("F. Scott Fitzgerald", g.author)
    }

    @Test fun skipsJunkLinesAndSubtitle() {
        val g = PdfFirstPageGuess.guessFromText("www.somesite.com\nFREE EBOOK\nTHE ALCHEMIST\nA Novel\nby Paulo Coelho\nCopyright 1988")!!
        assertEquals("The Alchemist", g.title)
        assertEquals("Paulo Coelho", g.author)
    }

    @Test fun wrappedTitle() {
        val g = PdfFirstPageGuess.guessFromText("Harry Potter and the\nSorcerer's Stone\nby J. K. Rowling")!!
        assertEquals("Harry Potter and the Sorcerer's Stone", g.title)
    }

    @Test fun noByLineFallsBackToFirstTitleLike() {
        val g = PdfFirstPageGuess.guessFromText("Dune\n\nISBN 978-0441013593\nCopyright")!!
        assertEquals("Dune", g.title)
        assertNull(g.author)
    }

    @Test fun nothingUsable() {
        assertNull(PdfFirstPageGuess.guessFromText("www.a.com\nDownload free eBooks\n12"))
        assertNotNull(PdfFirstPageGuess.guessFromText("Moby Dick"))
    }
}
