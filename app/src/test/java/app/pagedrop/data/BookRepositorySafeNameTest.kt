package app.pagedrop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Share/document providers control the display name; the stored file name must never be a path. */
class BookRepositorySafeNameTest {
    @Test
    fun knownBookExtensionsAreKept() {
        assertEquals("Dune - Frank Herbert.epub", DefaultBookRepository.safeStoredFileName("Dune - Frank Herbert.epub"))
        assertEquals("x.azw3", DefaultBookRepository.safeStoredFileName("x.AZW3"))
        assertEquals("AZW3", DefaultBookRepository.detectFormat(DefaultBookRepository.safeStoredFileName("x.azw3")))
    }

    @Test
    fun traversalAndSeparatorsAreRemoved() {
        val name = DefaultBookRepository.safeStoredFileName("../../shared_prefs/pagedrop_server.xml")
        assertFalse(name.contains('/'))
        assertFalse(name.contains(".."))
        assertEquals("pagedrop_server", name)
        assertEquals("evil.mobi", DefaultBookRepository.safeStoredFileName("../evil.mobi"))
    }

    @Test
    fun unknownExtensionsAreDroppedAndBlankNamesGetAFallback() {
        assertEquals("lib", DefaultBookRepository.safeStoredFileName("lib.so"))
        assertTrue(DefaultBookRepository.safeStoredFileName("..").startsWith("book_"))
        assertTrue(DefaultBookRepository.safeStoredFileName(null).startsWith("book_"))
    }
}
