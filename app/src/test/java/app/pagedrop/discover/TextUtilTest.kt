package app.pagedrop.discover

import org.junit.Assert.assertEquals
import org.junit.Test

class TextUtilTest {
    @Test
    fun simpleAuthorNamesAreReversed() {
        val names = mapOf(
            "Austen, Jane" to "Jane Austen",
            "Shelley, Mary Wollstonecraft" to "Mary Wollstonecraft Shelley",
            "Le Guin, Ursula K." to "Ursula K. Le Guin",
            "O'Connor, Flannery" to "Flannery O'Connor",
            "O’Neill, Eugene" to "Eugene O’Neill",
            "García Márquez, Gabriel" to "Gabriel García Márquez",
            "Толстой, Лев" to "Лев Толстой",
            "Smith-Jones, Anne-Marie" to "Anne-Marie Smith-Jones",
            "  Austen  ,  Jane  " to "Jane Austen",
        )
        names.forEach { (raw, expected) -> assertEquals(raw, expected, TextUtil.displayAuthor(raw, simpleOnly = true)) }
    }

    @Test
    fun namesWithoutCommasStayUnchanged() {
        for (raw in listOf("Anonymous", "Jane Austen", "Mary Wollstonecraft Shelley", "  Anonymous  ")) {
            assertEquals(raw, raw, TextUtil.displayAuthor(raw, simpleOnly = true))
        }
    }

    @Test
    fun multipleCommasAndDatesStayUnchanged() {
        for (raw in listOf(
            "Twain, Mark, 1835-1910", "Doe, Jane, Editor", "Austen, Jane,",
            "  Twain, Mark, 1835-1910  ", "Austen, Jane (1775-1817)", "Twain, 1835-1910",
        )) {
            assertEquals(raw, raw, TextUtil.displayAuthor(raw, simpleOnly = true))
        }
    }

    @Test
    fun organizationsStayUnchanged() {
        for (raw in listOf(
            "World Health Organization", "Example, Inc.", "ACME, LLC", "Example, Ltd.",
            "Department of Health, Education", "Oxford University Press, Editors", "Smith, John &amp; Co.",
        )) {
            assertEquals(raw, raw, TextUtil.displayAuthor(raw, simpleOnly = true))
        }
    }

    @Test
    fun suffixesAndQualifiersStayUnchanged() {
        for (raw in listOf(
            "King, Martin Luther Jr.", "King Jr., Martin Luther", "King, Martin Luther, Jr.",
            "Martin Luther King, Jr.", "Doe, John jr.", "Doe, John Sr.", "Doe, John III", "Doe, fl.",
        )) {
            assertEquals(raw, raw, TextUtil.displayAuthor(raw, simpleOnly = true))
        }
    }

    @Test
    fun emptyOrMalformedNamesStayUnchanged() {
        for (raw in listOf("", " ", ",", ", Jane", "Austen,", "Austen, , Jane", "Austen, ...", "123, Jane")) {
            assertEquals(raw, raw, TextUtil.displayAuthor(raw, simpleOnly = true))
        }
    }

    @Test
    fun normalizationIsIdempotent() {
        for (raw in listOf("Austen, Jane", "Shelley, Mary Wollstonecraft", "Twain, Mark, 1835-1910", "Example, Inc.")) {
            val once = TextUtil.displayAuthor(raw, simpleOnly = true)
            assertEquals(once, TextUtil.displayAuthor(once, simpleOnly = true))
        }
    }

    @Test
    fun otherProvidersKeepTheirExistingCatalogCleanup() {
        assertEquals("Mark Twain", TextUtil.displayAuthor("Twain, Mark, 1835-1910"))
        assertEquals("F. Scott Fitzgerald", TextUtil.displayAuthor("Fitzgerald, F. Scott (Francis Scott), 1896-1940"))
        assertEquals("Doe Jane Editor", TextUtil.displayAuthor("Doe, Jane, Editor"))
    }
}
