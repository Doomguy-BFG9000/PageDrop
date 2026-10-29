package app.pagedrop.share

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import app.pagedrop.converter.BookConverter
import app.pagedrop.data.BookRepository
import app.pagedrop.data.DefaultBookRepository
import app.pagedrop.data.SendFormatDecision
import app.pagedrop.data.SendFormatSetting
import app.pagedrop.data.SendPlan
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
        pageDropApiClient: PageDropApiClient? = null,
        sendFormat: SendFormatSetting = SendFormatSetting.CONVERT,
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
                        val ok = handleSharedFile(uri, application, bookRepository, sendFormat)
                        if (ok) successCount++ else errors.add("Failed: ${uri.lastPathSegment}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Shared file failed: ${e.javaClass.simpleName}")
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
     * and anything that is not a content URI. Those keep the silent add flow.
     */
    fun singleSharedFile(intent: Intent): Uri? {
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) return null
        return extractUris(intent).singleOrNull()
    }

    /**
     * Only content:// URIs are taken from share intents. MainActivity is exported, so any app can send it a
     * file:// URI pointing into this app's private storage (or elsewhere); those, and every other scheme, are
     * dropped before anything is read.
     */
    internal fun isAcceptedShareUri(uri: Uri?): Boolean = uri?.scheme.equals("content", ignoreCase = true)

    private fun extractUris(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.add(it) }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
        }
        if (intent.clipData != null) {
            for (i in 0 until intent.clipData!!.itemCount) {
                intent.clipData!!.getItemAt(i).uri?.let { uris.add(it) }
            }
        }
        intent.data?.let { uris.add(it) }
        val accepted = uris.distinct().filter(::isAcceptedShareUri)
        if (accepted.size != uris.distinct().size) Log.w(TAG, "Ignored shared URIs that are not content://")
        return accepted
    }

    /** Several shared files follow the Send format setting: Keep adds them as is (no embedding, no server). */
    internal fun shouldConvertShared(format: String, setting: SendFormatSetting): Boolean =
        SendFormatDecision.decide(format, setting, null) != SendPlan.KeepOriginal

    private suspend fun handleSharedFile(
        uri: Uri,
        application: Application,
        bookRepository: BookRepository,
        sendFormat: SendFormatSetting,
    ): Boolean {
        val displayName = getDisplayName(application, uri)
        val format = DefaultBookRepository.detectFormat(displayName ?: "")
        if (shouldConvertShared(format, sendFormat)) {
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
            // Null when the user kept conversion on the phone: nothing is sent, fall through to local extraction.
            val prepared = pageDropApiClient.convertArticleWithConsent(url, articlesDir)
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
