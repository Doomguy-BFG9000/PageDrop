package app.pagedrop.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.pagedrop.converter.BookConverter
import app.pagedrop.data.BookRepository
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.data.local.database.Book
import app.pagedrop.server.PageDropApiClient
import app.pagedrop.tools.articles.ArticleExtractor
import app.pagedrop.tools.articles.ArticleMobiConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object ShareHandler {
    private const val TAG = "ShareHandler"

    data class ShareResult(
        val successCount: Int,
        val errors: List<String>
    )

    suspend fun processSendIntent(
        intent: Intent,
        application: Application,
        bookRepository: BookRepository,
        pageDropApiClient: PageDropApiClient? = null
    ): ShareResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        var successCount = 0

        try {
            val uris = extractUris(intent)

            if (uris.isEmpty()) {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                    val firstUrl = text.trim().split("\\s+".toRegex()).firstOrNull { it.startsWith("http") }
                    if (firstUrl != null) {
                        val ok = handleSharedUrl(firstUrl, application, bookRepository, pageDropApiClient)
                        if (ok) successCount++ else errors.add("Failed to process URL")
                    } else {
                        val ok = handleSharedText(text, application, bookRepository)
                        if (ok) successCount++ else errors.add("Failed to save text")
                    }
                }
            } else {
                uris.forEach { uri ->
                    try {
                        val ok = handleSharedFile(uri, application, bookRepository)
                        if (ok) successCount++ else errors.add("Failed: ${uri.lastPathSegment}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed: ${uri.lastPathSegment}: ${e.message}", e)
                        errors.add("${uri.lastPathSegment}: ${e.localizedMessage ?: "Unknown error"}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Share processing failed", e)
            errors.add(e.localizedMessage ?: "Processing failed")
        }

        ShareResult(successCount = successCount, errors = errors)
    }

    /**
     * The one file a SEND/VIEW intent carries, or null for several files, text/URL shares
     * and anything that is not a content/file URI. Those keep the silent add flow.
     */
    fun singleSharedFile(intent: Intent): Uri? {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) return null
        val uri = extractUris(intent).singleOrNull() ?: return null
        return uri.takeIf { it.scheme == "content" || it.scheme == "file" }
    }

    private fun extractUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        if (intent.clipData != null) {
            for (i in 0 until intent.clipData!!.itemCount) {
                intent.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        intent.data?.let { uris.add(it) }
        return uris.distinct()
    }

    private suspend fun handleSharedFile(
        uri: Uri,
        application: Application,
        bookRepository: BookRepository
    ): Boolean {
        val displayName = getDisplayName(application, uri)
        val format = DefaultBookRepository.detectFormat(displayName ?: "")
        if (BookConverter.canConvert(format)) {
            bookRepository.convertAndAddBook(application, uri)
        } else {
            bookRepository.addBook(application, uri)
        }
        return true
    }

    private suspend fun handleSharedUrl(
        url: String,
        application: Application,
        bookRepository: BookRepository,
        pageDropApiClient: PageDropApiClient?
    ): Boolean {
        if (pageDropApiClient?.isAvailable == true) {
            val articlesDir = File(application.filesDir, "articles")
            articlesDir.mkdirs()
            val prepared = pageDropApiClient.convertArticleToLocalArtifact(url, articlesDir)
            if (prepared != null) {
                bookRepository.addBookFromFile(
                    Book(
                        title = prepared.title,
                        author = prepared.author,
                        fileName = prepared.file.name,
                        filePath = prepared.file.absolutePath,
                        format = prepared.format,
                        fileSize = prepared.file.length(),
                        coverPath = prepared.coverFile?.absolutePath
                    )
                )
                return true
            }
        }

        val extractResult = ArticleExtractor.extract(url)
        if (extractResult.isFailure) return false
        val article = extractResult.getOrThrow()
        val articlesDir = File(application.filesDir, "articles")
        articlesDir.mkdirs()
        val safeName = article.title.replace(Regex("[^a-zA-Z0-9\\s-]"), "").trim().take(50).ifBlank { "article" }
        val mobiFile = File(articlesDir, "${safeName}.mobi")
        if (!ArticleMobiConverter.convertArticleToMobi(article, mobiFile)) return false
        bookRepository.addBookFromFile(
            Book(
                title = article.title,
                author = article.author ?: (article.siteName ?: "Unknown Author"),
                fileName = mobiFile.name,
                filePath = mobiFile.absolutePath,
                format = "MOBI",
                fileSize = mobiFile.length()
            )
        )
        return true
    }

    private suspend fun handleSharedText(
        text: String,
        application: Application,
        bookRepository: BookRepository
    ): Boolean {
        val textsDir = File(application.filesDir, "texts")
        textsDir.mkdirs()
        val txtFile = File(textsDir, "shared_${System.currentTimeMillis()}.txt")
        txtFile.writeText(text, Charsets.UTF_8)
        val baseName = txtFile.nameWithoutExtension
        val mobiFile = File(textsDir, "$baseName.mobi")
        val result = BookConverter.convertToMobi(application, txtFile, mobiFile)
        txtFile.delete()
        if (!result.success) return false
        bookRepository.addBookFromFile(
            Book(
                title = "Shared Note",
                author = "Unknown Author",
                fileName = mobiFile.name,
                filePath = mobiFile.absolutePath,
                format = "MOBI",
                fileSize = mobiFile.length()
            )
        )
        return true
    }

    private fun getDisplayName(context: android.content.Context, uri: Uri): String? {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment
    }
}
