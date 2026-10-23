package app.pagedrop.tools.dictionaries

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DictionaryInstallerTest {

    @Test
    fun stardictValidation_detectsIfo() {
        val dir = createTempDirWithFiles("test_dict.ifo", "test_dict.idx")
        val files = dir.listFiles()?.toList() ?: emptyList()
        val hasIfo = files.any { it.extension.equals("ifo", true) }
        val hasIdx = files.any { it.extension.equals("idx", true) }
        assertTrue(hasIfo)
        assertTrue(hasIdx)
    }

    @Test
    fun stardictValidation_requiresIfo() {
        val dir = createTempDirWithFiles("dummy.txt")
        val files = dir.listFiles()?.toList() ?: emptyList()
        val hasIfo = files.any { it.extension.equals("ifo", true) }
        assertFalse(hasIfo)
    }

    @Test
    fun stardictValidation_requiresDictOrIdx() {
        val dir = createTempDirWithFiles("test.ifo", "readme.txt")
        val files = dir.listFiles()?.toList() ?: emptyList()
        val hasIdx = files.any { it.extension.equals("idx", true) }
        val hasDict = files.any {
            val name = it.name.lowercase()
            name.endsWith(".dict") || name.endsWith(".dict.dz")
        }
        assertFalse(hasIdx && hasDict)
    }

    @Test
    fun dictDotDz_detectedCorrectly() {
        assertTrue("test.dict.dz".lowercase().endsWith(".dict.dz"))
        assertFalse("test.dict".lowercase().endsWith(".dict.dz"))
    }

    // ── extractStarDict: archive entries are untrusted ──

    private sealed class Entry(val name: String) {
        class Regular(name: String, val bytes: ByteArray = "data".toByteArray()) : Entry(name)
        class Dir(name: String) : Entry(name)
        class Symlink(name: String, val target: String) : Entry(name)
        class HardLink(name: String, val target: String) : Entry(name)
        class Device(name: String) : Entry(name)
    }

    private fun archive(vararg entries: Entry): File {
        val file = File.createTempFile("dict", ".tar.bz2").apply { deleteOnExit() }
        TarArchiveOutputStream(BZip2CompressorOutputStream(file.outputStream())).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { e ->
                val entry = when (e) {
                    is Entry.Regular -> TarArchiveEntry(e.name).apply { size = e.bytes.size.toLong() }
                    is Entry.Dir -> TarArchiveEntry(e.name.trimEnd('/') + "/")
                    is Entry.Symlink -> TarArchiveEntry(e.name, TarConstants.LF_SYMLINK).apply { linkName = e.target }
                    is Entry.HardLink -> TarArchiveEntry(e.name, TarConstants.LF_LINK).apply { linkName = e.target }
                    is Entry.Device -> TarArchiveEntry(e.name, TarConstants.LF_CHR)
                }
                tar.putArchiveEntry(entry)
                if (e is Entry.Regular) tar.write(e.bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }

    private fun newDir(): File = java.nio.file.Files.createTempDirectory("dict_out").toFile().apply { deleteOnExit() }

    @Test
    fun extract_flattensNestedStarDictFiles() {
        val out = File(newDir(), "dict")
        val written = DictionaryInstaller.extractStarDict(
            archive(
                Entry.Dir("stardict-en/"),
                Entry.Regular("stardict-en/en.ifo"),
                Entry.Regular("stardict-en/en.idx"),
                Entry.Regular("stardict-en/en.dict.dz"),
            ),
            out,
        )
        assertEquals(setOf("en.ifo", "en.idx", "en.dict.dz"), written.map { it.name }.toSet())
        assertEquals(setOf("en.ifo", "en.idx", "en.dict.dz"), out.list()!!.toSet())
    }

    @Test
    fun extract_traversalNamesCannotLeaveTheDirectory() {
        val root = newDir()
        val out = File(root, "dict").apply { mkdirs() }
        DictionaryInstaller.extractStarDict(
            archive(
                Entry.Regular("../evil.ifo"),
                Entry.Regular("../../../../tmp/evil2.idx"),
                Entry.Regular("/abs/evil3.dict"),
                Entry.Regular("ok.ifo"),
            ),
            out,
        )
        assertFalse(File(root, "evil.ifo").exists())
        // Flattened: each lands inside the extraction dir under its base name only.
        assertEquals(setOf("evil.ifo", "evil2.idx", "evil3.dict", "ok.ifo"), out.list()!!.toSet())
        out.listFiles()!!.forEach { assertEquals(out.canonicalPath, it.canonicalFile.parent) }
    }

    @Test
    fun extract_skipsLinksDevicesAndOtherFileTypes() {
        val out = File(newDir(), "dict")
        val written = DictionaryInstaller.extractStarDict(
            archive(
                Entry.Regular("en.ifo"),
                Entry.Symlink("en.idx", "/etc/passwd"),
                Entry.HardLink("en.dict", "en.ifo"),
                Entry.Device("en.syn"),
                Entry.Regular("plugin.lua"),
                Entry.Regular("readme.txt"),
                Entry.Regular(".hidden.ifo"),
            ),
            out,
        )
        assertEquals(listOf("en.ifo"), written.map { it.name })
        assertEquals(listOf("en.ifo"), out.list()!!.toList())
    }

    @Test
    fun extract_refusesASymlinkedDestinationEscape() {
        val root = newDir()
        val outside = File(root, "outside").apply { mkdirs() }
        val out = File(root, "dict").apply { mkdirs() }
        // A pre-existing symlink inside the extraction dir must not be written through.
        java.nio.file.Files.createSymbolicLink(File(out, "en.ifo").toPath(), File(outside, "target.ifo").toPath())
        try {
            DictionaryInstaller.extractStarDict(archive(Entry.Regular("en.ifo")), out)
            fail("expected the symlinked target to be refused")
        } catch (expected: Exception) {
        }
        assertFalse(File(outside, "target.ifo").exists())
    }

    @Test
    fun extract_capsTotalSize() {
        val out = File(newDir(), "dict")
        try {
            DictionaryInstaller.extractStarDict(
                archive(Entry.Regular("a.ifo", ByteArray(600)), Entry.Regular("a.dict", ByteArray(600))),
                out,
                maxBytes = 1_000,
            )
            fail("expected the size cap to stop extraction")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun extract_capsEntryCount() {
        val out = File(newDir(), "dict")
        val many = (1..20).map { Entry.Regular("f$it.txt") }.toTypedArray()
        try {
            DictionaryInstaller.extractStarDict(archive(*many), out, maxEntries = 10)
            fail("expected the entry cap to stop extraction")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("too many"))
        }
    }

    @Test
    fun extract_rejectsDuplicateNamesAfterFlattening() {
        val out = File(newDir(), "dict")
        try {
            DictionaryInstaller.extractStarDict(archive(Entry.Regular("a/x.ifo"), Entry.Regular("b/x.ifo")), out)
            fail("expected a duplicate name to be refused")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("repeats"))
        }
    }

    private fun createTempDirWithFiles(vararg fileNames: String): File {
        val dir = java.io.File.createTempFile("dict_test", "")
        dir.delete()
        dir.mkdir()
        dir.deleteOnExit()
        fileNames.forEach { name ->
            val f = File(dir, name)
            f.writeText("content")
            f.deleteOnExit()
        }
        return dir
    }
}
