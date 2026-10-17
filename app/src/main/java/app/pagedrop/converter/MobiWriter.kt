package app.pagedrop.converter

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Writes a valid MOBI file using the PDB/PalmDOC/MOBI structure.
 *
 * Verified against a Calibre-generated MOBI that opens on Kindle PW1.
 * Key requirements discovered through testing:
 *   - PalmDOC compression (type 2) — Kindle PW1 may reject uncompressed
 *   - EXTH record 501 = "EBOK" — identifies content as ebook to Kindle
 *   - FLIS, FCIS, EOF magic records
 */
class MobiWriter(
    private val title: String,
    private val author: String,
    private val htmlContent: ByteArray,
    private val coverImage: ByteArray? = null,
    private val images: List<ByteArray> = emptyList(),
    /** Extra EXTH records (type -> payload), e.g. 121 for tests. */
    private val extraExth: Map<Int, ByteArray> = emptyMap(),
) {

    val kindleUuid: String get() = _kindleUuid
    private lateinit var _kindleUuid: String

    companion object {
        private const val TAG = "MobiWriter"
        private const val RECORD_SIZE = 4096
        private const val PDB_HEADER_SIZE = 78
        private const val MOBI_HEADER_LENGTH = 232
        private const val PDB_EPOCH_OFFSET = 2082844800L
        private const val COMPRESSION_PALMDOC = 2

        private val FLIS_RECORD = byteArrayOf(
            0x46, 0x4C, 0x49, 0x53,
            0x00, 0x00, 0x00, 0x08,
            0x00, 0x41, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0x00, 0x01, 0x00, 0x03,
            0x00, 0x00, 0x00, 0x03,
            0x00, 0x00, 0x00, 0x01,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()
        )

        private val EOF_RECORD = byteArrayOf(
            0xE9.toByte(), 0x8E.toByte(), 0x0D, 0x0A
        )
    }

    fun write(outputFile: File) {
        val textRecords = splitIntoRecords(htmlContent, RECORD_SIZE)
        val compressedRecords = textRecords.map { compressPalmDoc(it) }
        val textRecordCount = compressedRecords.size

        val allImages = mutableListOf<ByteArray>()
        if (coverImage != null) allImages.add(coverImage)
        allImages.addAll(images)

        val firstImageIndex = if (allImages.isNotEmpty()) textRecordCount + 1 else 0xFFFFFFFF.toInt()
        val lastContentRecord = if (allImages.isNotEmpty()) textRecordCount + allImages.size else textRecordCount

        val flisRecordIndex = 1 + textRecordCount + allImages.size
        val fcisRecordIndex = flisRecordIndex + 1
        val eofRecordIndex = fcisRecordIndex + 1
        val firstNonBookIndex = flisRecordIndex
        val totalRecords = eofRecordIndex + 1

        val fcisRecord = buildFcisRecord(htmlContent.size)

        val record0 = buildRecord0(
            textLength = htmlContent.size,
            textRecordCount = textRecordCount,
            firstImageIndex = firstImageIndex,
            firstNonBookIndex = firstNonBookIndex,
            lastContentRecord = lastContentRecord,
            flisRecordIndex = flisRecordIndex,
            fcisRecordIndex = fcisRecordIndex,
        )

        val allRecordData = mutableListOf<ByteArray>()
        allRecordData.add(record0)
        allRecordData.addAll(compressedRecords)
        allRecordData.addAll(allImages)
        allRecordData.add(FLIS_RECORD)
        allRecordData.add(fcisRecord)
        allRecordData.add(EOF_RECORD)

        val recordOffsetTableSize = totalRecords * 8 + 2
        var currentOffset = PDB_HEADER_SIZE + recordOffsetTableSize
        val offsets = mutableListOf<Int>()
        for (recordData in allRecordData) {
            offsets.add(currentOffset)
            currentOffset += recordData.size
        }

        Log.d(TAG, "Writing MOBI: records=$totalRecords text=$textRecordCount images=${allImages.size} record0=${record0.size}B")

        FileOutputStream(outputFile).use { fos ->
            DataOutputStream(fos).use { dos ->
                writePdbHeader(dos, totalRecords)

                for (i in 0 until totalRecords) {
                    dos.writeInt(offsets[i])
                    dos.writeByte(0)
                    dos.writeByte(0)
                    dos.writeShort(i)
                }
                dos.writeShort(0)

                for (recordData in allRecordData) {
                    dos.write(recordData)
                }
                dos.flush()
            }
        }
        Log.d(TAG, "MOBI written: ${outputFile.length()} bytes → ${outputFile.absolutePath}")
    }

    private fun writePdbHeader(dos: DataOutputStream, numRecords: Int) {
        val safeName = title.take(31).replace(Regex("[^\\x20-\\x7E]"), "_")
        val nameBytes = safeName.toByteArray(Charsets.US_ASCII)
        val nameField = ByteArray(32)
        System.arraycopy(nameBytes, 0, nameField, 0, minOf(nameBytes.size, 31))
        dos.write(nameField)

        dos.writeShort(0)
        dos.writeShort(0)
        val nowPdb = (System.currentTimeMillis() / 1000L) + PDB_EPOCH_OFFSET
        dos.writeInt(nowPdb.toInt())
        dos.writeInt(nowPdb.toInt())
        dos.writeInt(0)
        dos.writeInt(0)
        dos.writeInt(0)
        dos.writeInt(0)
        dos.write("BOOK".toByteArray(Charsets.US_ASCII))
        dos.write("MOBI".toByteArray(Charsets.US_ASCII))
        dos.writeInt(2 * numRecords + 1)
        dos.writeInt(0)
        dos.writeShort(numRecords)
    }

    private fun buildRecord0(
        textLength: Int,
        textRecordCount: Int,
        firstImageIndex: Int,
        firstNonBookIndex: Int,
        lastContentRecord: Int,
        flisRecordIndex: Int,
        fcisRecordIndex: Int,
    ): ByteArray {
        val baos = ByteArrayOutputStream(1024)
        val dos = DataOutputStream(baos)

        val exthData = buildExthHeader()
        val fullNameBytes = title.toByteArray(Charsets.UTF_8)
        val fullNameOffset = 16 + MOBI_HEADER_LENGTH + exthData.size

        // ── PalmDOC Header (16 bytes) ──
        dos.writeShort(COMPRESSION_PALMDOC)   //  0: compression = 2 (PalmDOC)
        dos.writeShort(0)                     //  2: unused
        dos.writeInt(textLength)              //  4: uncompressed text length
        dos.writeShort(textRecordCount)       //  8: record count
        dos.writeShort(RECORD_SIZE)           // 10: max record size (before compression)
        dos.writeShort(0)                     // 12: encryption (0=none)
        dos.writeShort(0)                     // 14: unknown

        // ── MOBI Header (232 bytes) ──
        dos.write("MOBI".toByteArray(Charsets.US_ASCII))
        dos.writeInt(MOBI_HEADER_LENGTH)
        dos.writeInt(2)                       // mobi type
        dos.writeInt(65001)                   // UTF-8
        dos.writeInt(title.hashCode())        // unique ID
        dos.writeInt(6)                       // file version

        dos.writeInt(-1)                      // ortho index
        dos.writeInt(-1)                      // inflection index
        dos.writeInt(-1)                      // index names
        dos.writeInt(-1)                      // index keys
        repeat(6) { dos.writeInt(-1) }        // extra indexes

        dos.writeInt(firstNonBookIndex)       // 80
        dos.writeInt(fullNameOffset)          // 84
        dos.writeInt(fullNameBytes.size)      // 88
        dos.writeInt(0x09)                    // 92: locale
        dos.writeInt(0)                       // 96
        dos.writeInt(0)                       //100
        dos.writeInt(6)                       //104: min version
        dos.writeInt(firstImageIndex)         //108

        dos.writeInt(0)                       //112: huffman
        dos.writeInt(0)                       //116
        dos.writeInt(0)                       //120
        dos.writeInt(0)                       //124

        dos.writeInt(0x50)                    //128: EXTH flags

        dos.write(ByteArray(32))              //132: 32 unknown bytes

        dos.writeInt(-1)                      //164
        dos.writeInt(-1)                      //168: DRM offset (no DRM)
        dos.writeInt(0)                       //172: DRM count
        dos.writeInt(0)                       //176: DRM size
        dos.writeInt(0)                       //180: DRM flags

        dos.write(ByteArray(8))               //184

        dos.writeShort(1)                     //192: first content record
        dos.writeShort(lastContentRecord)     //194: last content record

        dos.writeInt(1)                       //196

        dos.writeInt(fcisRecordIndex)         //200: FCIS
        dos.writeInt(1)                       //204
        dos.writeInt(flisRecordIndex)         //192: FLIS record number
        dos.writeInt(1)                       //196: FLIS count

        dos.write(ByteArray(8))               //200: unknown 8 bytes

        dos.writeInt(-1)                      //208: unknown (0xFFFFFFFF)
        dos.writeInt(0)                       //212: unknown (0x00000000)
        dos.writeInt(-1)                      //216: unknown (0xFFFFFFFF)
        dos.writeInt(-1)                      //220: unknown (0xFFFFFFFF)
        dos.writeInt(0)                       //224: Extra Record Data Flags = 0 (no trailing data)
        dos.writeInt(-1)                      //228: INDX record offset (0xFFFFFFFF = none)
        // End MOBI header (232 bytes total)

        dos.write(exthData)
        dos.write(fullNameBytes)
        dos.flush()

        // Pad to 4-byte boundary
        val raw = baos.toByteArray()
        val remainder = raw.size % 4
        if (remainder == 0) return raw
        val padded = ByteArray(raw.size + (4 - remainder))
        System.arraycopy(raw, 0, padded, 0, raw.size)
        return padded
    }

    private fun buildFcisRecord(textLength: Int): ByteArray {
        val baos = ByteArrayOutputStream(44)
        val dos = DataOutputStream(baos)
        dos.write("FCIS".toByteArray(Charsets.US_ASCII))
        dos.writeInt(0x00000014)
        dos.writeInt(0x00000010)
        dos.writeInt(0x00000001)
        dos.writeInt(0x00000000)
        dos.writeInt(textLength)
        dos.writeInt(0x00000000)
        dos.writeInt(0x00000020)
        dos.writeInt(0x00000008)
        dos.writeShort(0x0001)
        dos.writeShort(0x0001)
        dos.writeInt(0x00000000)
        dos.flush()
        return baos.toByteArray()
    }

    /**
     * EXTH header with critical records for Kindle compatibility:
     *   100 = author
     *   503 = updated title
     *   501 = "EBOK" (CDE type — tells Kindle this is an ebook)
     *   113 = UUID (unique identifier)
     *   201 = cover image offset (relative to the first image record) when a cover is supplied
     */
    private fun buildExthHeader(): ByteArray {
        val baos = ByteArrayOutputStream(256)
        val dos = DataOutputStream(baos)

        val uuid = UUID.randomUUID().toString()
        _kindleUuid = uuid
        val records = mutableListOf<ByteArray>()
        records.add(buildExthRecord(100, author.toByteArray(Charsets.UTF_8)))
        records.add(buildExthRecord(503, title.toByteArray(Charsets.UTF_8)))
        records.add(buildExthRecord(501, "EBOK".toByteArray(Charsets.UTF_8)))
        records.add(buildExthRecord(113, uuid.toByteArray(Charsets.UTF_8)))
        if (coverImage != null) {
            // Cover is the first image record: offset 0 from the first image index. 203 = no "fake cover".
            records.add(buildExthRecord(201, byteArrayOf(0, 0, 0, 0)))
            records.add(buildExthRecord(203, byteArrayOf(0, 0, 0, 0)))
        }
        for ((type, data) in extraExth) records.add(buildExthRecord(type, data))

        val recordsData = ByteArrayOutputStream()
        for (record in records) recordsData.write(record)
        val recordsBytes = recordsData.toByteArray()

        val headerLength = 12 + recordsBytes.size

        dos.write("EXTH".toByteArray(Charsets.US_ASCII))
        dos.writeInt(headerLength)
        dos.writeInt(records.size)
        dos.write(recordsBytes)

        val remainder = headerLength % 4
        if (remainder != 0) dos.write(ByteArray(4 - remainder))

        dos.flush()
        return baos.toByteArray()
    }

    private fun buildExthRecord(type: Int, data: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(data.size + 8)
        val dos = DataOutputStream(baos)
        dos.writeInt(type)
        dos.writeInt(data.size + 8)
        dos.write(data)
        dos.flush()
        return baos.toByteArray()
    }


    /**
     * PalmDOC LZ77 compression.
     * Ported from Calibre's py_compress_doc (src/calibre/ebooks/compression/palmdoc.py).
     *
     * Byte ranges in compressed stream:
     *   0x00:        literal null byte
     *   0x01-0x08:   next N bytes are literals (copy N bytes directly)
     *   0x09-0x7F:   literal byte (copy directly)
     *   0x80-0xBF:   2-byte LZ77 back-reference (11-bit distance + 3-bit length)
     *   0xC0-0xFF:   space + (byte XOR 0x80)
     */
    private fun compressPalmDoc(input: ByteArray): ByteArray {
        if (input.isEmpty()) return input

        val output = ByteArrayOutputStream(input.size)
        var i = 0
        val ldata = input.size

        while (i < ldata) {
            // Try LZ77 back-reference (only if enough data behind and ahead)
            if (i > 10 && (ldata - i) > 10) {
                var matchOffset = -1
                var matchLen = 0

                // Search for longest match, from length 10 down to 3
                for (j in 10 downTo 3) {
                    val chunk = input.copyOfRange(i, i + j)
                    val found = lastIndexOf(input, chunk, 0, i)
                    if (found >= 0 && (i - found) <= 2047) {
                        matchOffset = found
                        matchLen = j
                        break
                    }
                }

                if (matchOffset >= 0) {
                    val dist = i - matchOffset
                    // Encode as 2-byte big-endian value:
                    // 0x8000 + ((distance << 3) & 0x3FF8) + (length - 3)
                    val code = 0x8000 + ((dist shl 3) and 0x3FF8) + (matchLen - 3)
                    output.write((code shr 8) and 0xFF)
                    output.write(code and 0xFF)
                    i += matchLen
                    continue
                }
            }

            val ch = input[i].toInt() and 0xFF

            // Space + printable char optimization (0xC0-0xFF range)
            if (ch == 0x20 && i + 1 < ldata) {
                val next = input[i + 1].toInt() and 0xFF
                if (next in 0x40..0x7F) {
                    output.write(next xor 0x80)  // 0xC0-0xFF range
                    i += 2
                    continue
                }
            }

            // Bytes that need escaping: 0x00, 0x01-0x08, 0x80-0xFF
            if (ch == 0x00 || ch in 0x01..0x08 || ch >= 0x80) {
                // Use literal count prefix: 0x01 means "next 1 byte is literal"
                output.write(0x01)
                output.write(ch)
            } else {
                // 0x09-0x7F: safe literal, output directly
                output.write(ch)
            }
            i++
        }

        return output.toByteArray()
    }

    /** Find last occurrence of pattern in data[start..end). Returns -1 if not found. */
    private fun lastIndexOf(data: ByteArray, pattern: ByteArray, start: Int, end: Int): Int {
        if (pattern.isEmpty() || end - start < pattern.size) return -1
        outer@ for (pos in (end - pattern.size) downTo start) {
            for (k in pattern.indices) {
                if (data[pos + k] != pattern[k]) continue@outer
            }
            return pos
        }
        return -1
    }

    private fun splitIntoRecords(data: ByteArray, chunkSize: Int): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + chunkSize, data.size)
            records.add(data.copyOfRange(offset, end))
            offset = end
        }
        if (records.isEmpty()) records.add(ByteArray(0))
        return records
    }
}
