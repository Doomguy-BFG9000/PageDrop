package app.pagedrop.ui.book

import app.pagedrop.metadata.BookMetadataCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFormStateTitleTest {

    @Test
    fun sameTitle_ignoresLeadingArticleCaseAndPunctuation() {
        assertTrue(sameTitle("The Great Gatsby", "Great Gatsby"))
        assertTrue(sameTitle("the great gatsby!", "THE GREAT GATSBY"))
        assertFalse(sameTitle("The Great Gatsby", "Gatsby Returns"))
        assertFalse(sameTitle("", ""))
    }

    @Test
    fun apply_keepsExistingTitleWhenCatalogOnlyDropsArticle() {
        val form = MetadataFormState("The Great Gatsby", "F. Scott Fitzgerald")
        form.apply(BookMetadataCandidate(title = "Great Gatsby", author = "F. Scott Fitzgerald", coverUrl = "https://x/c.jpg"))
        assertEquals("The Great Gatsby", form.title)
        assertEquals("https://x/c.jpg", form.coverUrl)
    }

    @Test
    fun apply_replacesFilenameTitleAndPlaceholderAuthor() {
        val form = MetadataFormState("greatgatsby_ebook_free", "Unknown Author")
        form.apply(BookMetadataCandidate(title = "The Great Gatsby", author = "F. Scott Fitzgerald"))
        assertEquals("The Great Gatsby", form.title)
        assertEquals("F. Scott Fitzgerald", form.author)
    }

    @Test
    fun apply_keepsRealAuthorWhenCatalogHasNone() {
        val form = MetadataFormState("Some Book", "Jane Doe")
        form.apply(BookMetadataCandidate(title = "Some Book", author = "Unknown Author"))
        assertEquals("Jane Doe", form.author)
    }
}
