package app.pagedrop.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class SafeFilesTest {
    private val exts = setOf("epub", "mobi", "pdf")

    @Test
    fun safeFileName_keepsAPlainNameAndKnownExtension() {
        assertEquals("Dune - Frank Herbert.epub", SafeFiles.safeFileName("Dune - Frank Herbert.epub", exts))
        assertEquals("a.b.pdf", SafeFiles.safeFileName("a.b.PDF", exts))
    }

    @Test
    fun safeFileName_stripsDirectoriesAndDotSegments() {
        assertEquals("evil.epub", SafeFiles.safeFileName("../../shared_prefs/evil.epub", exts))
        assertEquals("evil.epub", SafeFiles.safeFileName("..\\..\\evil.epub", exts))
        assertEquals("book", SafeFiles.safeFileName("..", exts))
        assertEquals("book.epub", SafeFiles.safeFileName("..epub", exts))
        assertEquals("book", SafeFiles.safeFileName("", exts))
        assertEquals("book", SafeFiles.safeFileName(null, exts))
        assertEquals("hidden.epub", SafeFiles.safeFileName(".hidden.epub", exts))
        val weird = SafeFiles.safeFileName("a\u0000b:c*?\n.mobi", exts)
        assertFalse(weird.contains('\u0000') || weird.contains(':') || weird.contains('\n'))
        assertTrue(weird.endsWith(".mobi"))
    }

    @Test
    fun safeFileName_dropsUnknownExtensions() {
        assertEquals("payload", SafeFiles.safeFileName("payload.xml", exts))
        assertEquals("app", SafeFiles.safeFileName("app.so", exts))
        assertEquals("noext", SafeFiles.safeFileName("noext", exts))
    }

    @Test
    fun safeFileName_isBounded() {
        val long = "x".repeat(1000) + ".epub"
        assertTrue(SafeFiles.safeFileName(long, exts).length <= 125)
    }

    @Test
    fun childOf_refusesNamesThatLeaveTheDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("safe").toFile()
        listOf("..", ".", "", "../x", "a/b", "a\\b").forEach { name ->
            try {
                SafeFiles.childOf(dir, name)
                fail("expected $name to be refused")
            } catch (expected: SecurityException) {
            }
        }
        assertEquals(File(dir, "ok.epub"), SafeFiles.childOf(dir, "ok.epub"))
    }

    @Test
    fun childOf_refusesASymlinkPointingOutside() {
        val root = java.nio.file.Files.createTempDirectory("safe2").toFile()
        val dir = File(root, "books").apply { mkdirs() }
        java.nio.file.Files.createSymbolicLink(File(dir, "link.epub").toPath(), File(root, "outside.epub").toPath())
        try {
            SafeFiles.childOf(dir, "link.epub")
            fail("expected the symlink to be refused")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun uniqueChild_appendsACounterInsideTheDirectory() {
        val dir = java.nio.file.Files.createTempDirectory("safe3").toFile()
        File(dir, "a.epub").writeText("x")
        File(dir, "a_1.epub").writeText("x")
        assertEquals(File(dir, "a_2.epub"), SafeFiles.uniqueChild(dir, "a.epub"))
    }
}
