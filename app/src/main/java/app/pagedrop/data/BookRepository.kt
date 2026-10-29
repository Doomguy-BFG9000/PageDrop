/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.pagedrop.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import app.pagedrop.data.local.database.Book
import app.pagedrop.data.local.database.BookDao
import app.pagedrop.converter.BookConverter
import app.pagedrop.metadata.BookFileMetadataEmbedder
import app.pagedrop.metadata.BookMetadataLookup
import app.pagedrop.metadata.BookNameCleaner
import app.pagedrop.metadata.CoverImages
import app.pagedrop.metadata.MetadataLookupResult
import app.pagedrop.metadata.LookupQuery
import kotlinx.coroutines.flow.first
import app.pagedrop.metadata.EmbeddedMetadataReader
import app.pagedrop.server.PageDropApiClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import dagger.hilt.android.qualifiers.ApplicationContext
import app.pagedrop.security.SafeFiles
import java.io.File
import javax.inject.Inject

interface BookRepository {
    fun getBooks(): Flow<List<Book>>
    suspend fun addBook(context: Context, uri: Uri): Book
    suspend fun addBookFromFile(book: Book): Long
    suspend fun convertAndAddBook(context: Context, uri: Uri, outputTitle: String? = null): Book
    suspend fun deleteBook(book: Book)
    suspend fun getBookById(id: Int): Book?
    suspend fun markTransferred(uid: Int)
    suspend fun markTransferredBookIds(uids: List<Int>)
    suspend fun updateBookMetadata(book: Book, title: String, author: String, coverPath: String?): Book

    /**
     * Single entry point for "this is the real title/author/cover": updates the database row AND embeds
     * the metadata into the stored file (MOBI/AZW3/AZW EXTH + cover, PDF Info dictionary) and renames the file
     * to "Title - Author.ext". Embedding failures never lose the DB update; the book keeps its old file.
     * Safe to call from any dispatcher (does its own IO). Default: DB only.
     */
    suspend fun applyMetadata(book: Book, title: String, author: String, coverPath: String?): Book =
        updateBookMetadata(book, title, author, coverPath)

    /**
     * Imports a file downloaded by the Find-a-book flow or saved from the Kindle: moves [file] into filesDir/books,
     * saves the cover ([coverBytes] when given, else from [coverUrl] when reachable), inserts the row (format from
     * the extension) and embeds title/author/cover into AZW3/MOBI via [applyMetadata]. Catalog metadata is not a
     * user edit, so every other format (EPUB, PDF, ...) is stored byte for byte and keeps its file name; the
     * title/author/cover live in the library entry only. See [convertImported].
     */
    suspend fun importDownloaded(
        file: File,
        title: String,
        author: String,
        coverUrl: String?,
        coverBytes: ByteArray? = null,
    ): Book = throw UnsupportedOperationException("importDownloaded is not supported by this repository")

    /**
     * Gives library books that have no cover the one stored inside their own file (MOBI/AZW3/AZW/PRC/EPUB), when
     * there is one. Reads local files only, never the network. Returns how many books got a cover.
     */
    suspend fun fillMissingCoversFromFiles(): Int = 0

    /**
     * Looks up a cover online (Open Library, and Google Books when it is switched on) for each library book that
     * has none, skipping [skipUids]. A confident match's cover is saved for the library and Kindle thumbnail, and
     * is embedded into AZW3/MOBI files; other formats (PDF, EPUB, ...) are never rewritten for it.
     * Returns each book tried with whether a cover was found.
     */
    suspend fun findMissingCoversOnline(skipUids: Set<Int>): List<Pair<Int, Boolean>> = emptyList()

    /**
     * Converts a library book that is not Kindle-native (EPUB; also PDF/TXT) to MOBI with the existing server-or-phone
     * conversion, keeping its title, author and cover, and returns the new book. The original row and file are removed
     * only after the conversion succeeded. Kindle-native books are returned unchanged.
     */
    suspend fun convertImported(book: Book): Book = book

    /**
     * [addBook] then, when any of [title]/[author]/[coverPath] is given, [applyMetadata] if [embed] (the user edited
     * the details, or the file is Kindle-native), else [updateBookMetadata]: library entry only, file untouched.
     */
    suspend fun addBookWithMetadata(
        context: Context,
        uri: Uri,
        title: String? = null,
        author: String? = null,
        coverPath: String? = null,
        embed: Boolean = true,
    ): Book {
        val book = addBook(context, uri)
        if (title.isNullOrBlank() && author.isNullOrBlank() && coverPath == null) return book
        if (!embed) {
            return updateBookMetadata(book, title?.takeIf { it.isNotBlank() } ?: book.title, author?.takeIf { it.isNotBlank() } ?: book.author, coverPath ?: book.coverPath)
        }
        return applyMetadata(book, title?.takeIf { it.isNotBlank() } ?: book.title, author?.takeIf { it.isNotBlank() } ?: book.author, coverPath ?: book.coverPath)
    }

    /** [convertAndAddBook] with the chosen metadata baked into the new MOBI (title, author, cover). */
    suspend fun convertAndAddBookWithMetadata(
        context: Context,
        uri: Uri,
        title: String? = null,
        author: String? = null,
        coverPath: String? = null,
    ): Book {
        val book = convertAndAddBook(context, uri, title)
        if (author.isNullOrBlank() && coverPath == null) return book
        return applyMetadata(book, book.title, author?.takeIf { it.isNotBlank() } ?: book.author, coverPath ?: book.coverPath)
    }
}

class DefaultBookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val pageDropApiClient: PageDropApiClient? = null,
    @ApplicationContext private val appContext: Context? = null,
) : BookRepository {

    companion object {
        /** Formats whose files PageDrop rewrites with catalog metadata automatically; anything else stays byte for byte. */
        private val EMBED_ON_IMPORT_FORMATS = setOf("AZW3", "MOBI")
        private const val TAG = "BookRepository"
        private const val BOOKS_DIR = "books"
        private const val COVERS_DIR = "covers"
        private val EMBEDDED_COVER_FORMATS = setOf("MOBI", "AZW3", "AZW", "PRC", "EPUB")

        /** Supported ebook file extensions and their format names */
        private val FORMAT_MAP = mapOf(
            "azw3" to "AZW3",
            "mobi" to "MOBI",
            "pdf" to "PDF",
            "epub" to "EPUB",
            "txt" to "TXT",
            "azw" to "AZW",
            "kfx" to "KFX"
        )

        /** Extensions a stored library file may keep; any other extension from a provider's name is dropped. */
        internal val LIBRARY_EXTENSIONS: Set<String> =
            FORMAT_MAP.keys + setOf("prc", "pdb") + app.pagedrop.server.ServerFormats.OFFERED

        /**
         * The internal file name for an untrusted display name (from a share/document provider): a sanitized base name
         * plus a known extension, never a path separator or dot segment. See [SafeFiles.safeFileName].
         */
        internal fun safeStoredFileName(displayName: String?): String =
            SafeFiles.safeFileName(displayName, LIBRARY_EXTENSIONS, fallbackBase = "book_${System.currentTimeMillis()}")

        /**
         * Detects the ebook format from the file extension.
         */
        fun detectFormat(fileName: String): String {
            val ext = fileName.substringAfterLast(".", "").lowercase()
            return FORMAT_MAP[ext] ?: ext.uppercase().ifEmpty { "UNKNOWN" }
        }

        /**
         * Formats file size in human-readable form (B, KB, MB, GB).
         */
        fun formatFileSize(bytes: Long): String {
            return when {
                bytes < 1024 -> "$bytes B"
                bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
                bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
                else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
            }
        }
    }

    override fun getBooks(): Flow<List<Book>> = bookDao.getBooks()

    /**
     * Copies a file from the given content [uri] to app internal storage
     * (`context.filesDir/books/`), extracts metadata, and inserts a [Book] entity.
     */
    override suspend fun addBook(context: Context, uri: Uri): Book = withContext(Dispatchers.IO) {
        val booksDir = File(context.filesDir, BOOKS_DIR)
        if (!booksDir.exists()) {
            booksDir.mkdirs()
        }

        // The provider controls the display name: keep only a sanitized base name and a known extension.
        val displayName = safeStoredFileName(getDisplayName(context, uri))
        val format = detectFormat(displayName)

        // Generate unique filename to avoid collisions (always inside booksDir)
        val targetFile = generateUniqueFile(booksDir, displayName)

        // Copy file content from URI to internal storage
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open the shared file")

        val fileSize = targetFile.length()
        Log.d(TAG, "Copied book (${formatFileSize(fileSize)})")

        // Extract author from filename if possible (pattern: "Title - Author.ext")
        val nameWithoutExt = displayName.substringBeforeLast(".")
        val (title, author) = parseBookNameAndAuthor(nameWithoutExt)

        val book = Book(
            title = title,
            author = author,
            fileName = targetFile.name,
            filePath = targetFile.absolutePath,
            format = format,
            fileSize = fileSize
        )

        val id = bookDao.insertBook(book)
        Log.d(TAG, "Inserted book with id=$id")

        book.copy(uid = id.toInt())
    }

    /**
     * Deletes the book file from internal storage AND removes the database entry.
     */
    override suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        // Delete the file from disk
        val file = File(book.filePath)
        if (file.exists()) {
            val deleted = file.delete()
            Log.d(TAG, "Deleted book file: $deleted")
        }

        // Delete cover if it exists
        book.coverPath?.let { coverPath ->
            val coverFile = File(coverPath)
            if (coverFile.exists()) coverFile.delete()
        }

        // Remove from database
        bookDao.deleteBook(book)
        Log.d(TAG, "Deleted book id=${book.uid} from DB")
        Unit
    }

    /**
     * Copies a book file from the given content [uri], converts it to MOBI using
     * [BookConverter], saves any extracted cover image, and adds the resulting
     * MOBI file to the library. The intermediate source copy is deleted.
     * Supports EPUB, PDF, and TXT formats.
     */
    override suspend fun convertAndAddBook(
        context: Context,
        uri: Uri,
        outputTitle: String?
    ): Book = convertAndAddInternal(context, uri, outputTitle, null, null)

    override suspend fun convertAndAddBookWithMetadata(
        context: Context,
        uri: Uri,
        title: String?,
        author: String?,
        coverPath: String?
    ): Book = convertAndAddInternal(context, uri, title, author, coverPath)

    private suspend fun convertAndAddInternal(
        context: Context,
        uri: Uri,
        outputTitle: String?,
        outputAuthor: String?,
        chosenCoverPath: String?
    ): Book = withContext(Dispatchers.IO) {
        val chosenAuthor = outputAuthor?.trim()?.takeIf { it.isNotEmpty() && !BookNameCleaner.isPlaceholderAuthor(it) }
        val chosenCover = chosenCoverPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes()
            ?.takeIf { CoverImages.isImage(it) }
        val booksDir = File(context.filesDir, BOOKS_DIR)
        if (!booksDir.exists()) booksDir.mkdirs()

        val sourceDisplayName = safeStoredFileName(getDisplayName(context, uri))
        val displayName = outputTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                val ext = sourceDisplayName.substringAfterLast('.', "tmp")
                // "Title - Author.ext" so the Kindle filename carries both even if embedding fails
                BookFileMetadataEmbedder.fileNameFor(it, chosenAuthor.orEmpty(), ext)
            }
            ?: sourceDisplayName
        val format = detectFormat(displayName)
        Log.d(TAG, "Converting $format")

        if (!BookConverter.canConvert(format)) {
            throw IllegalStateException(
                "$format files cannot be converted to MOBI. Supported formats: EPUB, PDF, TXT."
            )
        }

        if (pageDropApiClient?.shouldUseServer(format) == true) {
            convertWithServer(context, uri, booksDir, displayName)?.let { served ->
                // The server's own metadata may be wrong too; embed what the user chose.
                if (outputTitle.isNullOrBlank() && chosenAuthor == null && chosenCoverPath == null) return@withContext served
                return@withContext applyMetadata(
                    served,
                    outputTitle?.trim()?.takeIf { it.isNotEmpty() } ?: served.title,
                    chosenAuthor ?: served.author,
                    chosenCoverPath ?: served.coverPath,
                )
            }
            Log.w(TAG, "Server conversion failed or timed out; falling back to local conversion")
        }

        val ext = displayName.substringAfterLast(".", "tmp").lowercase()
        val tempFile = File(booksDir, "_converting_${System.currentTimeMillis()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open the file to convert")

            val baseName = SafeFiles.safeBaseName(displayName.substringBeforeLast("."))
            val mobiFileName = "$baseName.mobi"
            val mobiFile = generateUniqueFile(booksDir, mobiFileName)

            val result = try {
                withTimeout(120_000L) {
                    BookConverter.convertToMobi(
                        context, tempFile, mobiFile,
                        title = outputTitle?.trim()?.takeIf { it.isNotEmpty() },
                        author = chosenAuthor,
                        coverImage = chosenCover,
                    )
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Conversion timed out after 120s")
                if (mobiFile.exists()) mobiFile.delete()
                throw IllegalStateException("Conversion timed out. The file may be too large or complex.")
            }

            if (!result.success) {
                throw IllegalStateException("$format to MOBI conversion failed for: $displayName")
            }

            // Save cover image if extracted
            val coverPath = if (chosenCover != null) chosenCoverPath else result.coverBytes?.let { bytes ->
                saveCoverBytes(context, bytes, baseName)
            }

            val fileSize = mobiFile.length()
            Log.d(TAG, "Converted to MOBI (${formatFileSize(fileSize)})")

            val (parsedTitle, parsedAuthor) = parseBookNameAndAuthor(baseName)
            val title = outputTitle?.trim()?.takeIf { it.isNotEmpty() } ?: parsedTitle
            val author = chosenAuthor ?: if (outputTitle.isNullOrBlank()) parsedAuthor else "Unknown Author"

            val book = Book(
                title = title,
                author = author,
                fileName = mobiFile.name,
                filePath = mobiFile.absolutePath,
                format = "MOBI",
                fileSize = fileSize,
                coverPath = coverPath,
                kindleUuid = result.kindleUuid
            )

            val id = bookDao.insertBook(book)
            Log.d(TAG, "Inserted converted book with id=$id")
            book.copy(uid = id.toInt())
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private suspend fun convertWithServer(
        context: Context,
        uri: Uri,
        booksDir: File,
        displayName: String
    ): Book? {
        val baseName = displayName.substringBeforeLast(".")
        val prepared = pageDropApiClient?.convertUriToLocalArtifact(
            uri = uri,
            outputDir = booksDir,
            targetFormat = "mobi",
            title = baseName
        ) ?: return null

        val coverPath = prepared.coverFile?.takeIf { it.exists() }?.let { coverFile ->
            saveCoverBytes(context, coverFile.readBytes(), prepared.file.nameWithoutExtension).also {
                coverFile.delete()
            }
        }

        val book = Book(
            title = prepared.title,
            author = prepared.author,
            fileName = prepared.file.name,
            filePath = prepared.file.absolutePath,
            format = prepared.format,
            fileSize = prepared.file.length(),
            coverPath = coverPath
        )

        val id = bookDao.insertBook(book)
        Log.d(TAG, "Inserted server-converted book with id=$id")
        return book.copy(uid = id.toInt())
    }

    /**
     * Saves cover image bytes to `context.filesDir/covers/{baseName}_cover.jpg`
     * and returns the absolute path.
     */
    private fun saveCoverBytes(context: Context, bytes: ByteArray, baseName: String): String? {
        return try {
            val coversDir = File(context.filesDir, COVERS_DIR)
            if (!coversDir.exists()) coversDir.mkdirs()
            val coverFile = SafeFiles.childOf(coversDir, "${SafeFiles.safeBaseName(baseName, "cover")}_cover.jpg")
            coverFile.outputStream().use { it.write(bytes) }
            Log.d(TAG, "Saved cover")
            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cover bytes: ${e.javaClass.simpleName}")
            null
        }
    }

    override suspend fun getBookById(id: Int): Book? = bookDao.getBookById(id)

    override suspend fun markTransferred(uid: Int) {
        bookDao.updateLastTransferred(uid, System.currentTimeMillis())
    }

    override suspend fun markTransferredBookIds(uids: List<Int>) {
        val timestamp = System.currentTimeMillis()
        uids.forEach { uid ->
            bookDao.updateLastTransferred(uid, timestamp)
        }
    }

    override suspend fun updateBookMetadata(book: Book, title: String, author: String, coverPath: String?): Book {
        val normalizedTitle = title.trim().ifBlank { book.title }
        val normalizedAuthor = author.trim().ifBlank { "Unknown Author" }
        bookDao.updateMetadata(book.uid, normalizedTitle, normalizedAuthor, coverPath)
        return book.copy(title = normalizedTitle, author = normalizedAuthor, coverPath = coverPath)
    }

    override suspend fun applyMetadata(book: Book, title: String, author: String, coverPath: String?): Book =
        withContext(Dispatchers.IO) {
            val normalizedTitle = title.trim().ifBlank { book.title }
            val normalizedAuthor = author.trim().ifBlank { "Unknown Author" }

            // 1. Embed into the stored file (copy + validate + swap; any failure leaves the file untouched).
            val file = File(book.filePath)
            val coverBytes = coverPath?.let { File(it) }?.takeIf { it.isFile }?.readBytes()?.takeIf { CoverImages.isImage(it) }
            val outcome = BookFileMetadataEmbedder.embed(file, book.format, normalizedTitle, normalizedAuthor, coverBytes)
            if (outcome.status == BookFileMetadataEmbedder.Status.FAILED) {
                Log.w(TAG, "Could not embed metadata into a ${book.format} file")
            }

            // 2. Rename to "Title - Author.ext" (only after a successful embed, so a failed embed keeps the old name).
            var target = file
            if (outcome.embedded && file.isFile) {
                val wanted = BookFileMetadataEmbedder.fileNameFor(normalizedTitle, normalizedAuthor, file.extension.ifEmpty { book.format })
                if (wanted != file.name) {
                    val dest = generateUniqueFile(file.parentFile ?: file.absoluteFile.parentFile, wanted)
                    if (file.renameTo(dest)) target = dest
                }
            }

            // 3. Database.
            val size = if (target.isFile) target.length() else book.fileSize
            val updated = book.copy(
                title = normalizedTitle,
                author = normalizedAuthor,
                coverPath = coverPath,
                fileName = target.name,
                filePath = target.absolutePath,
                fileSize = size,
            )
            try {
                bookDao.updateFileAndMetadata(
                    uid = book.uid,
                    fileName = target.name,
                    filePath = target.absolutePath,
                    fileSize = size,
                    title = normalizedTitle,
                    author = normalizedAuthor,
                    coverPath = coverPath,
                )
            } catch (e: Exception) {
                if (target != file) target.renameTo(file)
                throw e
            }
            updated
        }

    override suspend fun importDownloaded(
        file: File,
        title: String,
        author: String,
        coverUrl: String?,
        coverBytes: ByteArray?,
    ): Book =
        withContext(Dispatchers.IO) {
            val context = appContext ?: throw IllegalStateException("No application context available for import")
            val booksDir = File(context.filesDir, BOOKS_DIR).apply { mkdirs() }
            val target = generateUniqueFile(booksDir, file.name)
            if (!file.renameTo(target)) {
                file.copyTo(target, overwrite = true)
                file.delete()
            }
            val format = detectFormat(target.name)
            val cleanTitle = title.trim().ifBlank { target.nameWithoutExtension }
            val cleanAuthor = author.trim().ifBlank { "Unknown Author" }

            val coverPath = coverBytes?.takeIf { CoverImages.isUsableCover(it) }
                ?.let { saveCoverBytes(context, it, target.nameWithoutExtension) }
                ?: coverUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    BookMetadataLookup.downloadCoverBytes(url)?.let { saveCoverBytes(context, it, target.nameWithoutExtension) }
                }

            val book = Book(
                title = cleanTitle,
                author = cleanAuthor,
                fileName = target.name,
                filePath = target.absolutePath,
                format = format,
                fileSize = target.length(),
                coverPath = coverPath,
            )
            val inserted = book.copy(uid = bookDao.insertBook(book).toInt())
            Log.d(TAG, "Imported download as id=${inserted.uid}")

            if (format in EMBED_ON_IMPORT_FORMATS) {
                try {
                    applyMetadata(inserted, cleanTitle, cleanAuthor, coverPath)
                } catch (e: Exception) {
                    Log.w(TAG, "Metadata embedding failed for an imported $format: ${e.javaClass.simpleName}")
                    inserted
                }
            } else {
                inserted
            }
        }

    override suspend fun fillMissingCoversFromFiles(): Int = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext 0
        var filled = 0
        for (book in bookDao.getBooks().first()) {
            if (!book.coverPath.isNullOrBlank() && File(book.coverPath).isFile) continue
            if (book.format.uppercase() !in EMBEDDED_COVER_FORMATS) continue
            val file = File(book.filePath)
            if (!file.isFile) continue
            val bytes = EmbeddedMetadataReader.read(file, book.format).cover ?: continue
            val path = saveCoverBytes(context, bytes, file.nameWithoutExtension) ?: continue
            bookDao.updateMetadata(book.uid, book.title, book.author, path)
            filled++
        }
        if (filled > 0) Log.d(TAG, "Filled $filled missing cover(s) from book files")
        filled
    }

    /** Cover URL of a confident catalog match for [title]/[author]; a seam for tests. */
    internal var coverUrlLookup: suspend (String, String) -> String? = { title, author ->
        (BookMetadataLookup.lookup(LookupQuery(title, author)) as? MetadataLookupResult.Matches)?.best?.coverUrl
    }

    /** Downloads cover bytes; a seam for tests. */
    internal var coverDownload: (String) -> ByteArray? = { BookMetadataLookup.downloadCoverBytes(it) }

    override suspend fun findMissingCoversOnline(skipUids: Set<Int>): List<Pair<Int, Boolean>> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext emptyList()
        bookDao.getBooks().first()
            .filter { it.uid !in skipUids && (it.coverPath.isNullOrBlank() || !File(it.coverPath).isFile) }
            .map { book -> book.uid to (runCatching { coverFromCatalogs(context, book) }.getOrNull() == true) }
    }

    private suspend fun coverFromCatalogs(context: Context, book: Book): Boolean {
        val author = book.author.takeUnless { it.equals("Unknown Author", ignoreCase = true) }.orEmpty()
        val url = coverUrlLookup(book.title, author)?.takeIf { it.isNotBlank() } ?: return false
        val bytes = coverDownload(url)?.takeIf { CoverImages.isUsableCover(it) } ?: return false
        val path = saveCoverBytes(context, bytes, File(book.filePath).nameWithoutExtension) ?: return false
        if (book.format.uppercase() in EMBED_ON_IMPORT_FORMATS) {
            val embedded = runCatching { applyMetadata(book, book.title, book.author, path) }.isSuccess
            if (embedded) return true
        }
        bookDao.updateMetadata(book.uid, book.title, book.author, path)
        return true
    }

    override suspend fun convertImported(book: Book): Book = withContext(Dispatchers.IO) {
        val context = appContext ?: throw IllegalStateException("No application context available for conversion")
        if (!BookConverter.canConvert(book.format)) return@withContext book
        val source = File(book.filePath)
        if (!source.isFile) throw IllegalStateException("The downloaded file is missing: ${book.fileName}")

        val converted = convertAndAddInternal(
            context = context,
            uri = Uri.fromFile(source),
            outputTitle = book.title,
            outputAuthor = book.author,
            chosenCoverPath = book.coverPath,
        )
        // Conversion worked: drop the original row and file, but keep the cover, which the new book now uses.
        runCatching { bookDao.deleteBook(book) }
        if (source.absolutePath != converted.filePath) source.delete()
        converted
    }

    override suspend fun addBookFromFile(book: Book): Long {
        return bookDao.insertBook(book)
    }

    // ── Helpers ──────────────────────────────────────────────

    /**
     * Queries the content resolver for the display name of a URI.
     */
    private fun getDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    return cursor.getString(nameIndex)
                }
            }
        }
        // Fallback to last path segment
        return uri.lastPathSegment
    }

    /**
     * Generates a unique file in the target directory, appending a counter if needed. The result is always
     * canonically inside [dir]; a name that would leave it throws.
     */
    private fun generateUniqueFile(dir: File, name: String): File = SafeFiles.uniqueChild(dir, name)

    /**
     * Tries to parse "Title - Author" from the filename.
     * Falls back to using the whole name as title with "Unknown Author".
     */
    private fun parseBookNameAndAuthor(nameWithoutExt: String): Pair<String, String> {
        // Common pattern: "Title - Author" or "Author - Title"
        val separators = listOf(" - ", " — ", " – ")
        for (sep in separators) {
            if (nameWithoutExt.contains(sep)) {
                val parts = nameWithoutExt.split(sep, limit = 2)
                return Pair(parts[0].trim(), parts[1].trim())
            }
        }
        return Pair(nameWithoutExt.trim(), "Unknown Author")
    }
}
