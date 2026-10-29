package app.pagedrop.tools.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class KindleDeleteTest {
    private val gatsby = "/mnt/us/Books/Great Gatsby - F. Scott Fitzgerald.pdf"
    private val catcher = "/mnt/us/documents/The Catcher In The Rye.azw3"
    private val alice = "/mnt/us/documents/Alice's Adventures.mobi"
    private val allowed = setOf(gatsby, catcher, alice, "/mnt/us/Books/Series/Book One.epub")

    @Test fun acceptsRealNames() {
        listOf(gatsby, catcher, alice, "/mnt/us/Books/Series/Book One.epub").forEach {
            assertTrue(it, KindleDelete.isDeletableBookPath(it, allowed))
        }
    }

    @Test fun epubInKoreaderFolderIsDeletableButNotOutsideKnownFolders() {
        val epub = "/mnt/us/Books/Dune - Frank Herbert.epub"
        assertTrue(KindleDelete.isDeletableBookPath(epub, setOf(epub)))
        assertFalse(KindleDelete.isDeletableBookPath(epub, emptySet()))
        val stray = "/mnt/us/koreader/Dune.epub"
        assertFalse(KindleDelete.isDeletableBookPath(stray, setOf(stray)))
    }

    @Test fun rejectsBadPaths() {
        fun bad(p: String) = assertFalse(p, KindleDelete.isDeletableBookPath(p, allowed + p))
        bad("/mnt/us/documents")
        bad("/mnt/us/documents/../system/x.mobi")
        bad("/mnt/us/documents/./x.mobi")
        bad("/mnt/us/koreader/x.epub")
        bad("/mnt/us/documents/x.sh")
        bad("/mnt/us/documents/.mobi")
        bad("/mnt/us/documents/a/b/x.mobi")
        bad("/mnt/us/documents/x.sdr/y.mobi")
        bad("/mnt/us/documents//x.mobi")
        bad("/mnt/us/documents/x\ny.mobi")
        bad("/mnt/us/documents/x\u0000.mobi")
        bad("relative/x.mobi")
        bad("/mnt/us/documentsX/x.mobi")
    }

    @Test fun rejectsPathsNotInAllowedSet() {
        assertFalse(KindleDelete.isDeletableBookPath("/mnt/us/documents/Other.mobi", allowed))
        assertFalse(KindleDelete.isDeletableBookPath(gatsby, emptySet()))
    }

    @Test fun escapesApostrophe() {
        assertEquals("'Alice'\\''s Adventures.mobi'", KindleDelete.shellQuote("Alice's Adventures.mobi"))
        assertEquals("''\\'''", KindleDelete.shellQuote("'"))
    }

    @Test fun buildsExactCommands() {
        val script = KindleDelete.buildScript(listOf(alice, gatsby), deleteSidecars = true, allowed = allowed)
        val lines = script.lines()
        assertEquals(
            "if rm -f -- '/mnt/us/documents/Alice'\\''s Adventures.mobi' && [ ! -e '/mnt/us/documents/Alice'\\''s Adventures.mobi' ]; " +
                "then printf 'OK|%s\\n' '/mnt/us/documents/Alice'\\''s Adventures.mobi'; " +
                "else printf 'FAIL|%s\\n' '/mnt/us/documents/Alice'\\''s Adventures.mobi'; fi",
            lines[0]
        )
        assertTrue(lines[1].contains("[ -d '/mnt/us/documents/Alice'\\''s Adventures.sdr' ]"))
        assertTrue(lines[1].contains("rm -rf -- '/mnt/us/documents/Alice'\\''s Adventures.sdr'"))
        assertTrue(script.contains("rm -rf -- '/mnt/us/Books/Great Gatsby - F. Scott Fitzgerald.sdr'"))
        assertEquals("true", lines.last())
        // rm -rf only ever targets .sdr folders
        script.lines().filter { it.contains("rm -rf") }.forEach { assertTrue(it.contains(".sdr'")) }
        assertFalse(script.contains("*"))
    }

    @Test fun noSidecarsWhenNotRequested() {
        val script = KindleDelete.buildScript(listOf(catcher), deleteSidecars = false, allowed = allowed)
        assertFalse(script.contains("rm -rf"))
        assertFalse(script.contains(".sdr"))
    }

    @Test fun thumbnailsOnlyForSafeIds() {
        val script = KindleDelete.buildScript(listOf(catcher), false, allowed, listOf("abc-123", "x'; rm -rf /; '"))
        assertTrue(script.contains("rm -f -- '/mnt/us/system/thumbnails/thumbnail_abc-123_EBOK_portrait.jpg'"))
        assertEquals(1, script.lines().count { it.contains("thumbnails") })
    }

    @Test fun buildRefusesAnyBadPath() {
        try {
            KindleDelete.buildScript(listOf(catcher, "/mnt/us/documents/x.sh"), false, allowed + "/mnt/us/documents/x.sh")
            fail("expected refusal")
        } catch (_: IllegalArgumentException) {
        }
        try {
            KindleDelete.buildScript(emptyList(), false, allowed)
            fail("expected refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun sidecarPath() {
        assertEquals("/mnt/us/Books/Great Gatsby - F. Scott Fitzgerald.sdr", KindleDelete.sidecarFor(gatsby))
        assertEquals("/mnt/us/Books/Series/Book One.sdr", KindleDelete.sidecarFor("/mnt/us/Books/Series/Book One.epub"))
        assertNull(KindleDelete.sidecarFor("/mnt/us/koreader/x.epub"))
    }

    @Test fun parsesPartialFailures() {
        val out = "OK|$catcher\nFAIL|$gatsby\nFAIL|/mnt/us/documents/Alice.sdr\n"
        val o = KindleDelete.parseOutput(out, listOf(catcher, gatsby, alice))
        assertEquals(listOf(catcher), o.deleted)
        assertEquals(listOf(gatsby, alice), o.failed)
        assertEquals(listOf("/mnt/us/documents/Alice.sdr"), o.sidecarsFailed)
        assertFalse(o.allOk)
        assertTrue(KindleDelete.parseOutput("OK|$catcher\n", listOf(catcher)).allOk)
    }
}
