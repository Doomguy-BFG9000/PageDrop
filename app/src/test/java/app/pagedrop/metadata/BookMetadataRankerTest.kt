package app.pagedrop.metadata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookMetadataRankerTest {
    private fun c(title: String, author: String, cover: Boolean = true) =
        BookMetadataCandidate(title, author, coverUrl = if (cover) "https://x/c.jpg" else null)

    @Test fun exactTitleAndAuthorIsHighConfidence() {
        val q = LookupQuery("The Great Gatsby", "F. Scott Fitzgerald")
        val conf = BookMetadataRanker.confidence(q, c("The Great Gatsby", "F. Scott Fitzgerald"))
        assertTrue("conf=$conf", conf >= 0.95f)
    }

    @Test fun titleOnlyIsHighButNotCertain() {
        val conf = BookMetadataRanker.confidence(LookupQuery("the great gatsby"), c("The Great Gatsby", "F. Scott Fitzgerald"))
        assertTrue("conf=$conf", conf in 0.8f..0.95f)
    }

    @Test fun wrongAuthorDropsBelowThreshold() {
        val q = LookupQuery("The Great Gatsby", "Somebody Else")
        val conf = BookMetadataRanker.confidence(q, c("The Great Gatsby", "F. Scott Fitzgerald"))
        assertTrue("conf=$conf", conf < 0.9f)
    }

    @Test fun differentBookIsLow() {
        val conf = BookMetadataRanker.confidence(LookupQuery("The Great Gatsby"), c("Great Expectations", "Charles Dickens"))
        assertTrue("conf=$conf", conf < 0.6f)
    }

    @Test fun subtitleDoesNotHurt() {
        val conf = BookMetadataRanker.confidence(LookupQuery("Dune"), c("Dune: Deluxe Edition", "Frank Herbert"))
        assertTrue("conf=$conf", conf >= BookMetadataRanker.HIGH_CONFIDENCE)
    }

    @Test fun swappedOrderIsHandledForFilenames() {
        val q = LookupQuery.from("Agustina Bazterrica - Tender is the Flesh")
        val conf = BookMetadataRanker.confidence(q, c("Tender Is the Flesh", "Agustina Bazterrica"))
        assertTrue("conf=$conf", conf >= BookMetadataRanker.HIGH_CONFIDENCE)
        val q2 = LookupQuery.from("Tender is the Flesh - Agustina Bazterrica")
        assertTrue(BookMetadataRanker.confidence(q2, c("Tender Is the Flesh", "Agustina Bazterrica")) >= 0.9f)
    }

    @Test fun typoTolerated() {
        val conf = BookMetadataRanker.confidence(LookupQuery("Flowers for Algernom", "Daniel Keyes"), c("Flowers for Algernon", "Daniel Keyes"))
        assertTrue("conf=$conf", conf >= BookMetadataRanker.HIGH_CONFIDENCE)
    }

    @Test fun lossyIdFilenameIsNeverAutoAccepted() {
        val q = LookupQuery.from("Ticket_TK1010718726t83")
        val conf = BookMetadataRanker.confidence(q, c("Ticket", "Someone"))
        assertTrue("conf=$conf", conf < BookMetadataRanker.HIGH_CONFIDENCE)
    }

    @Test fun rankSortsAndKeepsOrderOnTies() {
        val ranked = BookMetadataRanker.rank(
            LookupQuery("Dune", "Frank Herbert"),
            listOf(c("Dune Messiah", "Frank Herbert"), c("Dune", "Frank Herbert"), c("Dune", "Frank Herbert", cover = false)),
        )
        assertEquals("Dune", ranked[0].title)
        assertTrue(ranked[0].coverUrl != null)
        assertEquals("Dune Messiah", ranked.last().title)
    }

    @Test fun isbnMatchWins() {
        val q = LookupQuery("", isbn = "9780743273565")
        val conf = BookMetadataRanker.confidence(q, BookMetadataCandidate("Whatever", "A", isbn = "9780743273565"))
        assertTrue(conf >= 0.9f)
    }

    @Test fun queryFromUserTypedTitleIsUntouched() {
        val q = LookupQuery.from("The Great Gatsby", "Unknown Author")
        assertEquals("The Great Gatsby", q.title)
        assertEquals("", q.author)
        assertNull(q.isbn)
        assertNotNull(q)
    }
}
