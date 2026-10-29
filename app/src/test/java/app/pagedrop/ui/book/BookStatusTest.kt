package app.pagedrop.ui.book

import app.pagedrop.data.local.database.Book
import app.pagedrop.ui.components.StatusTone
import app.pagedrop.ui.components.initialsOf
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookStatusTest {

    private fun book(lastTransferred: Long? = null) = Book(
        uid = 1, title = "T", author = "A", fileName = "f.mobi", filePath = "/f.mobi",
        format = "MOBI", fileSize = 10, lastTransferred = lastTransferred,
    )

    @Test
    fun status_notSentYet() {
        assertEquals(BookStatusText("Not sent yet", StatusTone.Normal), bookStatus(book(), BookTransfer.Idle))
    }

    @Test
    fun status_onKindleShowsDate() {
        val millis = 1_759_449_600_000L // 3 Oct 2025 UTC, noon-safe in any zone
        val status = bookStatus(book(millis), BookTransfer.Idle)
        assertEquals("On Kindle · ${formatShortDate(millis)}", status.text)
        assertEquals(StatusTone.Normal, status.tone)
    }

    @Test
    fun status_sendingAndFailedOverrideDate() {
        assertEquals(StatusTone.Active, bookStatus(book(5L), BookTransfer.Sending).tone)
        assertEquals(StatusTone.Error, bookStatus(book(5L), BookTransfer.Failed).tone)
    }

    @Test
    fun filters_splitByLastTransferred() {
        assertTrue(LibraryFilter.All.matches(book()))
        assertTrue(LibraryFilter.NotOnKindle.matches(book()))
        assertFalse(LibraryFilter.OnKindle.matches(book()))
        assertTrue(LibraryFilter.OnKindle.matches(book(1L)))
        assertFalse(LibraryFilter.NotOnKindle.matches(book(1L)))
    }

    @Test
    fun addPlan_matchesPlainLanguageDecisions() {
        assertEquals(AddPlan.KindleReady, addPlanFor("MOBI"))
        assertEquals(AddPlan.KindleReady, addPlanFor("azw3"))
        assertEquals(AddPlan.KindleReady, addPlanFor("PRC"))
        assertEquals(AddPlan.Choice, addPlanFor("EPUB"))
        assertEquals(AddPlan.Choice, addPlanFor("TXT"))
        assertEquals(AddPlan.Choice, addPlanFor("PDF"))
        assertEquals(AddPlan.Other, addPlanFor("CBZ"))
    }

    @Test
    fun initials_useFirstTwoWords() {
        assertEquals("DQ", initialsOf("Don Quixote of La Mancha"))
        assertEquals("M", initialsOf("moby"))
        assertEquals("?", initialsOf("   "))
    }
}
