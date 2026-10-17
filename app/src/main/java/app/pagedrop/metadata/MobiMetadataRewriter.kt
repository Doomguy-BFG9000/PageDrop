package app.pagedrop.metadata

import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Rewrites title / author (and optionally the cover image) of a MOBI / AZW / AZW3 file without
 * re-converting it. Pure JVM so it is unit-testable.
 *
 * What changes: EXTH 503 (updated title), EXTH 100 (author), the record-0 "full name" field and the PDB
 * name for the title; for KF8 joint files (EXTH 121 boundary) the KF8 record 0 gets the same edits. When an
 * EXTH cover offset (201) exists, the referenced image record is swapped for the new JPEG/PNG. Everything else
 * (text, indexes, images) is copied byte for byte and the PDB record table is rebuilt.
 *
 * The output is validated by re-parsing; callers should use [tryRewrite] which never leaves a bad file.
 */
object MobiMetadataRewriter {
    class MobiFormatException(message: String) : IOException(message)

    data class Info(
        val title: String?,
        val author: String?,
        val fullName: String?,
        val kf8Boundary: Int?,
        val recordCount: Int,
        val coverRecord: Int?,
    )

    private const val EXTH_AUTHOR = 100
    private const val EXTH_COVER_OFFSET = 201
    private const val EXTH_KF8_BOUNDARY = 121
    private const val EXTH_UPDATED_TITLE = 503
    private const val MAX_RECORD0 = 1 shl 20

    /** Reads the metadata we care about. Throws [MobiFormatException] for non-MOBI input. */
    fun read(file: File): Info = RandomAccessFile(file, "r").use { raf ->
        val pdb = Pdb.open(raf)
        val rec0 = Rec0.parse(pdb.record(raf, 0))
        val boundary = rec0.kf8Boundary(pdb)
        Info(
            title = rec0.exthString(EXTH_UPDATED_TITLE),
            author = rec0.exthString(EXTH_AUTHOR),
            fullName = rec0.fullName(),
            kf8Boundary = boundary,
            recordCount = pdb.count,
            coverRecord = coverRecordIndex(raf, pdb, rec0, 0),
        )
    }

    /**
     * Writes [dst] as a copy of [src] with the new metadata, then validates it.
     * Returns true on success. On any problem [dst] is removed and false is returned (the original is untouched).
     */
    fun tryRewrite(src: File, dst: File, title: String?, author: String?, cover: ByteArray? = null): Boolean =
        try {
            rewrite(src, dst, title, author, cover)
            true
        } catch (e: Exception) {
            dst.delete()
            false
        }

    /** Like [tryRewrite] but throws (and may leave a partial [dst]); [BookFileMetadataEmbedder] cleans up. */
    fun rewrite(src: File, dst: File, title: String?, author: String?, cover: ByteArray? = null) {
        val newTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        val newAuthor = author?.trim()?.takeIf { it.isNotEmpty() }
        val newCover = cover?.takeIf { CoverImages.isImage(it) }

        RandomAccessFile(src, "r").use { raf ->
            val pdb = Pdb.open(raf)
            val replacements = HashMap<Int, ByteArray>()

            val rec0 = Rec0.parse(pdb.record(raf, 0))
            replacements[0] = rec0.withMetadata(newTitle, newAuthor)
            val boundary = rec0.kf8Boundary(pdb)
            var kf8Rec0: Rec0? = null
            if (boundary != null) {
                kf8Rec0 = runCatching { Rec0.parse(pdb.record(raf, boundary)) }.getOrNull()
                kf8Rec0?.let { replacements[boundary] = it.withMetadata(newTitle, newAuthor) }
            }
            if (newCover != null) {
                val parts = listOfNotNull(0 to rec0, kf8Rec0?.let { boundary!! to it })
                for ((base, r) in parts) {
                    coverRecordIndex(raf, pdb, r, base)?.let { replacements[it] = newCover }
                }
            }

            val newOffsets = LongArray(pdb.count)
            var cursor = pdb.offsets[0]
            for (i in 0 until pdb.count) {
                newOffsets[i] = cursor
                cursor += replacements[i]?.size ?: pdb.size(i)
            }

            val header = ByteArray(pdb.offsets[0].toInt())
            raf.seek(0)
            raf.readFully(header)
            for (i in 0 until pdb.count) putU32(header, 78 + 8 * i, newOffsets[i])
            if (newTitle != null) patchPdbName(header, newTitle)

            BufferedOutputStream(dst.outputStream(), 1 shl 16).use { out ->
                out.write(header)
                val buffer = ByteArray(1 shl 16)
                for (i in 0 until pdb.count) {
                    val replacement = replacements[i]
                    if (replacement != null) {
                        out.write(replacement)
                    } else {
                        raf.seek(pdb.offsets[i])
                        var left = pdb.size(i)
                        while (left > 0) {
                            val n = raf.read(buffer, 0, minOf(buffer.size, left))
                            if (n < 0) throw MobiFormatException("Unexpected end of file")
                            out.write(buffer, 0, n)
                            left -= n
                        }
                    }
                }
            }
            validate(raf, pdb, dst, replacements, newTitle, newAuthor, boundary)
        }
    }

    private fun validate(
        srcRaf: RandomAccessFile, srcPdb: Pdb, dst: File, replacements: Map<Int, ByteArray>,
        title: String?, author: String?, boundary: Int?,
    ) {
        RandomAccessFile(dst, "r").use { raf ->
            val out = Pdb.open(raf)
            if (out.count != srcPdb.count) throw MobiFormatException("Record count changed")
            for (i in 0 until out.count) {
                val got = out.record(raf, i)
                val expected = replacements[i] ?: srcPdb.record(srcRaf, i)
                if (!got.contentEquals(expected)) throw MobiFormatException("Record $i differs after rewrite")
            }
            for (base in listOfNotNull(0, boundary)) {
                val r = Rec0.parse(out.record(raf, base))
                if (title != null && (r.exthString(EXTH_UPDATED_TITLE) != title || r.fullName() != title)) {
                    throw MobiFormatException("Title did not round-trip")
                }
                if (author != null && r.exthString(EXTH_AUTHOR) != author) {
                    throw MobiFormatException("Author did not round-trip")
                }
            }
        }
    }

    private fun patchPdbName(header: ByteArray, title: String) {
        val ascii = title.map { if (it.code in 0x20..0x7E) it else '_' }.joinToString("").take(31)
        for (i in 0 until 32) header[i] = 0
        ascii.toByteArray(Charsets.US_ASCII).copyInto(header)
    }

    /** Absolute record index of the cover image referenced by EXTH 201 of [rec0] (record 0 of its part is [base]). */
    private fun coverRecordIndex(raf: RandomAccessFile, pdb: Pdb, rec0: Rec0, base: Int): Int? {
        val offset = rec0.exthInt(EXTH_COVER_OFFSET) ?: return null
        if (rec0.b.size < 112) return null
        val first = rec0.u32(108).toInt()
        if (first <= 0) return null
        return listOf(first + offset, base + first + offset).distinct().firstOrNull { idx ->
            idx in 1 until pdb.count && pdb.size(idx) > 8 && run {
                val head = ByteArray(8)
                raf.seek(pdb.offsets[idx])
                raf.readFully(head)
                CoverImages.isImage(head)
            }
        }
    }

    // ── PDB container ────────────────────────────────────────────────────────

    private class Pdb(val count: Int, val offsets: LongArray) {
        fun size(i: Int): Int = (offsets[i + 1] - offsets[i]).toInt()

        fun record(raf: RandomAccessFile, i: Int): ByteArray {
            if (i !in 0 until count) throw MobiFormatException("No record $i")
            val bytes = ByteArray(size(i))
            raf.seek(offsets[i])
            raf.readFully(bytes)
            return bytes
        }

        companion object {
            fun open(raf: RandomAccessFile): Pdb {
                val length = raf.length()
                if (length < 80) throw MobiFormatException("Too small for a PDB file")
                val head = ByteArray(78)
                raf.seek(0)
                raf.readFully(head)
                val type = String(head, 60, 8, Charsets.ISO_8859_1)
                if (type != "BOOKMOBI") throw MobiFormatException("Not a MOBI file ($type)")
                val count = ((head[76].toInt() and 0xFF) shl 8) or (head[77].toInt() and 0xFF)
                if (count < 2) throw MobiFormatException("Too few records")
                val table = ByteArray(8 * count)
                raf.readFully(table)
                val offsets = LongArray(count + 1)
                for (i in 0 until count) offsets[i] = u32(table, 8 * i)
                offsets[count] = length
                if (offsets[0] < 78L + 8 * count) throw MobiFormatException("Bad first record offset")
                for (i in 0 until count) {
                    if (offsets[i + 1] < offsets[i] || offsets[i] > length) throw MobiFormatException("Bad record offsets")
                }
                return Pdb(count, offsets)
            }
        }
    }

    // ── Record 0 ─────────────────────────────────────────────────────────────

    private class ExthRecord(val type: Int, val data: ByteArray)

    private class Rec0(
        val b: ByteArray,
        val exthStart: Int,
        val exthEnd: Int,
        val exth: List<ExthRecord>,
    ) {
        val charset: Charset = if (u32(28) == 1252L) Charset.forName("windows-1252") else Charsets.UTF_8

        fun u32(off: Int): Long = u32(b, off)

        fun exthString(type: Int): String? = exth.firstOrNull { it.type == type }?.data?.toString(charset)

        fun exthInt(type: Int): Int? =
            exth.firstOrNull { it.type == type }?.data?.takeIf { it.size == 4 }?.let { u32(it, 0).toInt() }

        fun fullName(): String? {
            val off = u32(84)
            val len = u32(88)
            if (off <= 0 || off + len > b.size) return null
            return String(b, off.toInt(), len.toInt(), charset)
        }

        fun kf8Boundary(pdb: Pdb): Int? = exthInt(EXTH_KF8_BOUNDARY)?.takeIf { it in 2 until pdb.count }

        fun withMetadata(title: String?, author: String?): ByteArray {
            if (title == null && author == null) return b
            val fnOffset = u32(84)
            val fnLen = u32(88)
            if (fnOffset < exthEnd || fnOffset + fnLen > b.size) throw MobiFormatException("Unsupported full-name layout")

            val records = ArrayList(exth)
            fun set(type: Int, value: String?) {
                if (value == null) return
                val data = value.toByteArray(charset)
                val firstIdx = records.indexOfFirst { it.type == type }
                if (firstIdx < 0) {
                    records.add(ExthRecord(type, data))
                } else {
                    records[firstIdx] = ExthRecord(type, data)
                    for (i in records.size - 1 downTo firstIdx + 1) if (records[i].type == type) records.removeAt(i)
                }
            }
            set(EXTH_AUTHOR, author)
            set(EXTH_UPDATED_TITLE, title)
            val newExth = buildExth(records)

            val name = title?.toByteArray(charset) ?: b.copyOfRange(fnOffset.toInt(), (fnOffset + fnLen).toInt())
            val gap = b.copyOfRange(exthEnd, fnOffset.toInt())
            val tail = b.copyOfRange((fnOffset + fnLen).toInt(), b.size)
            var out = b.copyOfRange(0, exthStart) + newExth + gap + name + tail
            putU32(out, 84, (exthStart + newExth.size + gap.size).toLong())
            putU32(out, 88, name.size.toLong())
            putU32(out, 128, u32(out, 128) or 0x40L) // EXTH present
            if (out.size % 4 != 0) out = out.copyOf(out.size + (4 - out.size % 4))
            if (out.size > MAX_RECORD0) throw MobiFormatException("Record 0 too large")
            return out
        }

        companion object {
            fun parse(b: ByteArray): Rec0 {
                if (b.size < 132 || String(b, 16, 4, Charsets.ISO_8859_1) != "MOBI") throw MobiFormatException("No MOBI header")
                val encryption = ((b[12].toInt() and 0xFF) shl 8) or (b[13].toInt() and 0xFF)
                if (encryption != 0) throw MobiFormatException("DRM-protected books cannot be edited")
                val headerLen = u32(b, 20).toInt()
                if (headerLen < 116 || 16 + headerLen > b.size) throw MobiFormatException("Bad MOBI header length")
                val exthStart = 16 + headerLen
                val hasExth = (u32(b, 128) and 0x40L) != 0L
                if (!hasExth) return Rec0(b, exthStart, exthStart, emptyList())
                if (exthStart + 12 > b.size || String(b, exthStart, 4, Charsets.ISO_8859_1) != "EXTH") {
                    throw MobiFormatException("EXTH flag set but no EXTH block")
                }
                val len = u32(b, exthStart + 4).toInt()
                val count = u32(b, exthStart + 8).toInt()
                if (len < 12 || exthStart + len > b.size || count < 0 || count > 10_000) throw MobiFormatException("Bad EXTH length")
                val end = minOf(exthStart + ((len + 3) and 3.inv()), b.size)
                val records = ArrayList<ExthRecord>(count)
                var p = exthStart + 12
                repeat(count) {
                    if (p + 8 > exthStart + len) throw MobiFormatException("Truncated EXTH")
                    val type = u32(b, p).toInt()
                    val rlen = u32(b, p + 4).toInt()
                    if (rlen < 8 || p + rlen > exthStart + len) throw MobiFormatException("Bad EXTH record")
                    records.add(ExthRecord(type, b.copyOfRange(p + 8, p + rlen)))
                    p += rlen
                }
                return Rec0(b, exthStart, end, records)
            }

            fun buildExth(records: List<ExthRecord>): ByteArray {
                val len = 12 + records.sumOf { 8 + it.data.size }
                val out = ByteArray((len + 3) and 3.inv())
                "EXTH".toByteArray(Charsets.ISO_8859_1).copyInto(out)
                putU32(out, 4, len.toLong())
                putU32(out, 8, records.size.toLong())
                var p = 12
                for (r in records) {
                    putU32(out, p, r.type.toLong() and 0xFFFFFFFFL)
                    putU32(out, p + 4, (8 + r.data.size).toLong())
                    r.data.copyInto(out, p + 8)
                    p += 8 + r.data.size
                }
                return out
            }
        }
    }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or (b[off + 3].toLong() and 0xFF)

    private fun putU32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte()
        b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte()
        b[off + 3] = v.toByte()
    }
}
