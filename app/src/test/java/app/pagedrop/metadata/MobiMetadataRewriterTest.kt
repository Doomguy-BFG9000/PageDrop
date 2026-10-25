package app.pagedrop.metadata

import app.pagedrop.converter.MobiWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class MobiMetadataRewriterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val jpeg = ByteArray(3000) { if (it < 3) listOf(0xFF, 0xD8, 0xFF)[it].toByte() else (it % 251).toByte() }
    private val jpeg2 = ByteArray(5000) { if (it < 3) listOf(0xFF, 0xD8, 0xFF)[it].toByte() else ((it * 7) % 251).toByte() }

    private fun synthetic(title: String = "flowersforalgernon", author: String = "Unknown Author", cover: ByteArray? = null, extra: Map<Int, ByteArray> = emptyMap()): File {
        val html = "<html><body>" + "<p>Hello world paragraph.</p>".repeat(2000) + "</body></html>"
        val f = tmp.newFile()
        MobiWriter(title, author, html.toByteArray(), coverImage = cover, extraExth = extra).write(f)
        return f
    }

    private fun records(file: File): List<ByteArray> = RandomAccessFile(file, "r").use { raf ->
        val n = raf.readShortAt(76)
        val offs = (0 until n).map { raf.readIntAt(78 + 8 * it).toLong() and 0xFFFFFFFFL } + raf.length()
        (0 until n).map { i -> ByteArray((offs[i + 1] - offs[i]).toInt()).also { raf.seek(offs[i]); raf.readFully(it) } }
    }

    private fun RandomAccessFile.readIntAt(p: Int): Int { seek(p.toLong()); return readInt() }
    private fun RandomAccessFile.readShortAt(p: Int): Int { seek(p.toLong()); return readUnsignedShort() }

    @Test fun updatesTitleAndAuthorOnPhoneMadeMobi() {
        val src = synthetic()
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, "Flowers for Algernon", "Daniel Keyes")
        val info = MobiMetadataRewriter.read(dst)
        assertEquals("Flowers for Algernon", info.title)
        assertEquals("Daniel Keyes", info.author)
        assertEquals("Flowers for Algernon", info.fullName)
        // all text records identical
        val a = records(src); val b = records(dst)
        assertEquals(a.size, b.size)
        for (i in 1 until a.size) assertArrayEquals("record $i", a[i], b[i])
        assertNotEquals(a[0].size, b[0].size)
        assertEquals("Flowers for Algernon", String(b0name(dst), Charsets.UTF_8))
    }

    private fun b0name(f: File): ByteArray {
        val name = ByteArray(32); RandomAccessFile(f, "r").use { it.readFully(name) }
        return name.takeWhile { it != 0.toByte() }.toByteArray()
    }

    @Test fun shrinkingTitleAlsoWorks() {
        val src = synthetic(title = "A very very long working title that will shrink", author = "Someone Quite Long Named")
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, "Dune", "FH")
        val info = MobiMetadataRewriter.read(dst)
        assertEquals("Dune", info.title); assertEquals("FH", info.author)
        assertTrue(dst.length() < src.length())
    }

    @Test fun unicodeTitle() {
        val src = synthetic()
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, "Les Misérables — 悲惨世界", "Victor Hugo")
        assertEquals("Les Misérables — 悲惨世界", MobiMetadataRewriter.read(dst).fullName)
    }

    @Test fun titleOnlyKeepsAuthor() {
        val src = synthetic(author = "Keep Me")
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, "New Title", null)
        assertEquals("Keep Me", MobiMetadataRewriter.read(dst).author)
    }

    @Test fun replacesCoverRecordAndShiftsOffsets() {
        val src = synthetic(cover = jpeg)
        assertEquals(1 + 0, 1) // sanity
        val before = MobiMetadataRewriter.read(src)
        assertNotNull(before.coverRecord)
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, "T", "A", jpeg2)
        val b = records(dst); val a = records(src)
        assertArrayEquals(jpeg2, b[before.coverRecord!!])
        assertArrayEquals(jpeg, a[before.coverRecord!!])
        // everything after the cover is intact and still addressable
        for (i in (before.coverRecord!! + 1) until a.size) assertArrayEquals(a[i], b[i])
        assertEquals(before.coverRecord, MobiMetadataRewriter.read(dst).coverRecord)
    }

    @Test fun nonImageCoverIsIgnored() {
        val src = synthetic(cover = jpeg)
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, "T", "A", ByteArray(100) { 7 })
        assertArrayEquals(records(src)[MobiMetadataRewriter.read(src).coverRecord!!], records(dst)[MobiMetadataRewriter.read(src).coverRecord!!])
    }

    @Test fun kf8JointFileUpdatesBothHeaders() {
        // Build KF7 part + BOUNDARY + KF8 part by concatenating two writer outputs.
        val kf8 = synthetic(title = "kf8 title", author = "kf8 author", cover = jpeg)
        val kf7Probe = synthetic(title = "kf7 title", author = "kf7 author", cover = jpeg)
        val kf7Records = records(kf7Probe)
        val boundaryIndex = kf7Records.size + 1
        val kf7 = synthetic(title = "kf7 title", author = "kf7 author", cover = jpeg, extra = mapOf(121 to byteArrayOf(0, 0, 0, boundaryIndex.toByte())))
        val all = records(kf7) + "BOUNDARY".toByteArray() + records(kf8)
        val joint = writePdb(all, kf7)
        val info = MobiMetadataRewriter.read(joint)
        assertEquals(boundaryIndex, info.kf8Boundary)

        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(joint, dst, "Joint Title", "Joint Author", jpeg2)
        val out = records(dst)
        assertEquals(all.size, out.size)
        val r0 = MobiMetadataRewriter.read(dst)
        assertEquals("Joint Title", r0.fullName)
        // KF8 record 0 rewritten: read it by making it a standalone file view
        val kf8Rec0 = out[boundaryIndex]
        assertTrue(String(kf8Rec0, Charsets.ISO_8859_1).contains("Joint Author"))
        assertTrue(String(kf8Rec0, Charsets.ISO_8859_1).contains("Joint Title"))
        assertFalse(String(kf8Rec0, Charsets.ISO_8859_1).contains("kf8 title"))
        assertArrayEquals("BOUNDARY".toByteArray(), out[boundaryIndex - 1])
    }

    private fun writePdb(recs: List<ByteArray>, headerFrom: File): File {
        val head = ByteArray(78); RandomAccessFile(headerFrom, "r").use { it.readFully(head) }
        head[76] = (recs.size shr 8).toByte(); head[77] = recs.size.toByte()
        val out = ByteArrayOutputStream()
        out.write(head)
        var off = 78 + 8 * recs.size + 2
        recs.forEachIndexed { i, r ->
            out.write(byteArrayOf((off shr 24).toByte(), (off shr 16).toByte(), (off shr 8).toByte(), off.toByte(), 0, 0, (i shr 8).toByte(), i.toByte()))
            off += r.size
        }
        out.write(0); out.write(0)
        recs.forEach { out.write(it) }
        return tmp.newFile().also { it.writeBytes(out.toByteArray()) }
    }

    @Test fun nonMobiInputFailsCleanly() {
        val junk = tmp.newFile().apply { writeBytes(ByteArray(500) { 1 }) }
        val dst = File(tmp.root, "out.mobi")
        assertFalse(MobiMetadataRewriter.tryRewrite(junk, dst, "T", "A"))
        assertFalse(dst.exists())
    }

    @Test fun truncatedMobiFailsCleanly() {
        val src = synthetic()
        val cut = tmp.newFile().apply { writeBytes(src.readBytes().copyOf(200)) }
        val dst = File(tmp.root, "out2.mobi")
        assertFalse(MobiMetadataRewriter.tryRewrite(cut, dst, "T", "A"))
        assertFalse(dst.exists())
    }

    @Test fun embedderLeavesOriginalOnFailure() {
        val junk = tmp.newFile("x.mobi").apply { writeBytes(ByteArray(500) { 1 }) }
        val before = junk.readBytes()
        val outcome = BookFileMetadataEmbedder.embed(junk, "MOBI", "T", "A", null)
        assertEquals(BookFileMetadataEmbedder.Status.FAILED, outcome.status)
        assertArrayEquals(before, junk.readBytes())
        assertFalse(File(junk.parentFile, ".x.mobi.meta.tmp").exists())
    }

    // ── Real files (skipped when the scratch books are not on this machine) ──

    private val realDir = File("/private/tmp/claude-501").walkTopDown().maxDepth(6)
        .firstOrNull { it.isDirectory && it.name == "books" && File(it, "of-mice-and-men.azw3").exists() }

    private fun realRoundTrip(name: String, title: String, author: String) {
        val src = File(realDir ?: return assumeTrue("real fixtures not present", false), name)
        assumeTrue(src.exists())
        val before = MobiMetadataRewriter.read(src)
        val dst = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, dst, title, author)
        val after = MobiMetadataRewriter.read(dst)
        println("REAL $name: '${before.title}' / '${before.author}' -> '${after.title}' / '${after.author}', size ${src.length()} -> ${dst.length()}, records ${before.recordCount}")
        assertEquals(title, after.title); assertEquals(author, after.author); assertEquals(title, after.fullName)
        assertEquals(before.recordCount, after.recordCount)
        val a = records(src); val b = records(dst)
        for (i in 1 until a.size) assertArrayEquals("$name record $i", a[i], b[i])
    }

    @Test fun realKf8() = realRoundTrip("of-mice-and-men.azw3", "Of Mice and Men", "John Steinbeck")
    @Test fun realKf8Catcher() = realRoundTrip("The Catcher In The Rye.azw3", "The Catcher in the Rye", "J. D. Salinger")
    @Test fun realKf7Mobi() = realRoundTrip("The Hunger Games.mobi", "The Hunger Games", "Suzanne Collins")
    @Test fun realSmallMobi() = realRoundTrip("flowersforalgernon.mobi", "Flowers for Algernon", "Daniel Keyes")
    @Test fun realRepeatedRewriteIsStable() {
        val src = File(realDir ?: return assumeTrue("real fixtures not present", false), "of-mice-and-men.azw3")
        val once = tmp.newFile(); val twice = tmp.newFile()
        MobiMetadataRewriter.rewrite(src, once, "X", "Y")
        MobiMetadataRewriter.rewrite(once, twice, "X", "Y")
        assertArrayEquals(once.readBytes(), twice.readBytes().also { assertNull(null) })
    }


    @Test
    fun readCoverReturnsTheEmbeddedImage() {
        val src = synthetic(cover = jpeg)
        assertArrayEquals(jpeg, MobiMetadataRewriter.readCover(src))
    }

    @Test
    fun readCoverIsNullWithoutACover() {
        assertNull(MobiMetadataRewriter.readCover(synthetic()))
    }

    @Test
    fun embeddedReaderPassesTheMobiCoverThrough() {
        val src = synthetic(title = "The Hunger Games", author = "Suzanne Collins", cover = jpeg)
        assertArrayEquals(jpeg, EmbeddedMetadataReader.read(src, "MOBI").cover)
        assertArrayEquals(jpeg, EmbeddedMetadataReader.read(src, "AZW3").cover)
    }
}
