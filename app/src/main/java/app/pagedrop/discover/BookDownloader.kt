package app.pagedrop.discover

import android.content.Context
import app.pagedrop.metadata.BookFileMetadataEmbedder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.UnknownHostException
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

/** A download failed; [message] is plain words suitable for the UI. */
class DownloadException(message: String, val offline: Boolean = false, cause: Throwable? = null) : IOException(message, cause)

@Singleton
class BookDownloader internal constructor(
    private val downloadsDir: File,
    private val http: DiscoverHttp,
    private val credentials: (catalogId: String, url: String) -> BasicAuth?,
) {
    @Inject
    constructor(
        @ApplicationContext ctx: Context,
        http: DiscoverHttp,
        settings: SourceSettings,
    ) : this(File(ctx.cacheDir, "downloads"), http, { id, url -> settings.credentialsFor(id, url) })

    /**
     * Streams [option] to a temp file in cacheDir/downloads, reporting progress (0f..1f, or null when the size is
     * unknown; invoked on an IO thread), validates the file's magic bytes against the claimed format, caps the size at
     * 300 MB, and returns "<Title> - <Author>.<ext>". The caller moves or imports it. Throws [DownloadException].
     */
    suspend fun download(result: BookResult, option: DownloadOption, onProgress: (Float?) -> Unit): File =
        withContext(Dispatchers.IO) {
            downloadsDir.mkdirs()
            val temp = File(downloadsDir, "dl_${System.nanoTime()}.part")
            try {
                stream(result, option, temp, onProgress)
                validate(temp, option.format)
                val name = BookFileMetadataEmbedder.fileNameFor(result.title, result.author, option.format.extension)
                val target = File(downloadsDir, name).also { if (it.exists()) it.delete() }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                onProgress(1f)
                target
            } catch (e: DownloadException) {
                temp.delete(); throw e
            } catch (e: kotlinx.coroutines.CancellationException) {
                temp.delete(); throw e
            } catch (e: UnknownHostException) {
                temp.delete(); throw DownloadException("No connection. Check your internet and try again.", offline = true, cause = e)
            } catch (e: SocketTimeoutException) {
                temp.delete(); throw DownloadException("The download timed out. Try again.", cause = e)
            } catch (e: IOException) {
                temp.delete(); throw DownloadException("The download was interrupted. Try again.", cause = e)
            }
        }

    private suspend fun stream(result: BookResult, option: DownloadOption, temp: File, onProgress: (Float?) -> Unit) {
        val auth = authFor(result, option.url)
        http.open(option.url, auth).use { response ->
            when {
                response.code == 401 || response.code == 403 ->
                    throw DownloadException(if (auth != null) "The catalog rejected the saved sign-in." else "This download needs permission, so it can't be fetched here.")
                response.code == 404 || response.code == 410 -> throw DownloadException("That file is no longer available at ${result.sourceName}.")
                response.code !in 200..299 -> throw DownloadException("${result.sourceName} couldn't send the file (HTTP ${response.code}).")
            }
            val total = response.contentLength.takeIf { it > 0 }
            if (total != null && total > MAX_BYTES) throw DownloadException("This file is larger than 300 MB, which is more than PageDrop will download.")
            var written = 0L
            var lastPercent = -1
            onProgress(if (total != null) 0f else null)
            temp.outputStream().use { out ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = response.input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    if (written > MAX_BYTES) throw DownloadException("This file is larger than 300 MB, which is more than PageDrop will download.")
                    if (total != null) {
                        val percent = (written * 100 / total).toInt().coerceAtMost(100)
                        if (percent != lastPercent) { lastPercent = percent; onProgress(percent / 100f) }
                    }
                }
            }
            if (total != null && written < total) throw DownloadException("The download was cut short. Try again.")
        }
    }

    /** Credentials go only to the catalog's own host, never to a redirect target or another site. */
    private fun authFor(result: BookResult, url: String): BasicAuth? {
        if (result.source != SourceKind.OPDS) return null
        val parts = result.id.split(':', limit = 3)
        val catalogId = parts.getOrNull(1) ?: return null
        return credentials(catalogId, url)
    }

    internal fun validate(file: File, format: BookFormat) {
        val head = ByteArray(4096)
        val read = file.inputStream().use { it.read(head).coerceAtLeast(0) }
        if (read == 0) throw DownloadException("The downloaded file was empty.")
        val ok = when (format) {
            BookFormat.PDF -> head.indexOfAscii("%PDF-", read).let { it in 0..1023 }
            BookFormat.EPUB -> read >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
            BookFormat.AZW3, BookFormat.MOBI -> read >= 68 && head.indexOfAscii("BOOKMOBI", read) == 60
            BookFormat.TXT -> looksLikeText(head, read)
        }
        if (!ok) {
            val html = String(head, 0, minOf(read, 512), Charsets.ISO_8859_1).trimStart().let {
                it.startsWith("<!DOCTYPE", true) || it.startsWith("<html", true) || it.startsWith("<?xml", true)
            }
            throw DownloadException(
                if (html) "The site sent a web page instead of the book. Try another format or source."
                else "The downloaded file isn't a valid ${format.name} book.",
            )
        }
    }

    private fun looksLikeText(b: ByteArray, n: Int): Boolean {
        if (String(b, 0, minOf(n, 512), Charsets.ISO_8859_1).trimStart().let {
                it.startsWith("<!DOCTYPE", true) || it.startsWith("<html", true)
            }) return false
        return (0 until n).none { b[it] == 0.toByte() }
    }

    private fun ByteArray.indexOfAscii(s: String, limit: Int): Int {
        val needle = s.toByteArray(Charsets.ISO_8859_1)
        outer@ for (i in 0..(limit - needle.size)) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    companion object {
        const val MAX_BYTES = 300L * 1024 * 1024
    }
}
