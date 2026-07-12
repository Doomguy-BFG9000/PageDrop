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
import app.pagedrop.server.PageDropApiClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
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
}

class DefaultBookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val pageDropApiClient: PageDropApiClient? = null
) : BookRepository {

    companion object {
        private const val TAG = "BookRepository"
        private const val BOOKS_DIR = "books"
        private const val COVERS_DIR = "covers"

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

        // Extract filename from content resolver
        val displayName = getDisplayName(context, uri) ?: "unknown_${System.currentTimeMillis()}"
        val format = detectFormat(displayName)

        // Generate unique filename to avoid collisions
        val targetFile = generateUniqueFile(booksDir, displayName)

        // Copy file content from URI to internal storage
        context.contentResolver.openInputStream(uri)?.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalStateException("Could not open input stream for URI: $uri")

        val fileSize = targetFile.length()
        Log.d(TAG, "Copied book: $displayName (${formatFileSize(fileSize)}) → ${targetFile.absolutePath}")

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
        Log.d(TAG, "Inserted book with id=$id: $title by $author")

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
            Log.d(TAG, "Deleted file ${book.filePath}: $deleted")
        }

        // Delete cover if it exists
        book.coverPath?.let { coverPath ->
            val coverFile = File(coverPath)
            if (coverFile.exists()) coverFile.delete()
        }

        // Remove from database
        bookDao.deleteBook(book)
        Log.d(TAG, "Deleted book from DB: ${book.title}")
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
    ): Book = withContext(Dispatchers.IO) {
        val booksDir = File(context.filesDir, BOOKS_DIR)
        if (!booksDir.exists()) booksDir.mkdirs()

        val sourceDisplayName = getDisplayName(context, uri) ?: "unknown_${System.currentTimeMillis()}"
        val displayName = outputTitle
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it.${sourceDisplayName.substringAfterLast('.', "tmp")}" }
            ?: sourceDisplayName
        val format = detectFormat(displayName)
        Log.d(TAG, "Converting $format: $displayName")

        if (!BookConverter.canConvert(format)) {
            throw IllegalStateException(
                "$format files cannot be converted to MOBI. Supported formats: EPUB, PDF, TXT."
            )
        }

        if (pageDropApiClient?.shouldUseServer(format) == true) {
            convertWithServer(context, uri, booksDir, displayName)?.let { return@withContext it }
            Log.w(TAG, "Server conversion failed or timed out; falling back to local conversion for $displayName")
        }

        val ext = displayName.substringAfterLast(".", "tmp").lowercase()
        val tempFile = File(booksDir, "_converting_${System.currentTimeMillis()}.$ext")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Could not open input stream for URI: $uri")

            val baseName = displayName.substringBeforeLast(".")
            val mobiFileName = "$baseName.mobi"
            val mobiFile = generateUniqueFile(booksDir, mobiFileName)

            val result = try {
                withTimeout(120_000L) {
                    BookConverter.convertToMobi(context, tempFile, mobiFile)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Conversion timed out after 120s for: $displayName")
                if (mobiFile.exists()) mobiFile.delete()
                throw IllegalStateException("Conversion timed out. The file may be too large or complex.")
            }

            if (!result.success) {
                throw IllegalStateException("$format to MOBI conversion failed for: $displayName")
            }

            // Save cover image if extracted
            val coverPath = result.coverBytes?.let { bytes ->
                saveCoverBytes(context, bytes, baseName)
            }

            val fileSize = mobiFile.length()
            Log.d(TAG, "Converted: $displayName → ${mobiFile.name} (${formatFileSize(fileSize)})")

            val (title, author) = parseBookNameAndAuthor(baseName)

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
            Log.d(TAG, "Inserted converted book with id=$id: $title by $author")
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
        Log.d(TAG, "Inserted server-converted book with id=$id: ${book.title}")
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
            val coverFile = File(coversDir, "${baseName}_cover.jpg")
            coverFile.outputStream().use { it.write(bytes) }
            Log.d(TAG, "Saved cover: ${coverFile.absolutePath}")
            coverFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cover bytes: ${e.message}", e)
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
     * Generates a unique file in the target directory, appending a counter if needed.
     */
    private fun generateUniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file

        val baseName = name.substringBeforeLast(".")
        val extension = name.substringAfterLast(".", "")
        var counter = 1
        while (file.exists()) {
            val newName = if (extension.isNotEmpty()) "${baseName}_$counter.$extension" else "${baseName}_$counter"
            file = File(dir, newName)
            counter++
        }
        return file
    }

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
