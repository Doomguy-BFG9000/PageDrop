package app.pagedrop.tools.dictionaries

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import app.pagedrop.security.SafeFiles
import java.util.concurrent.TimeUnit
import app.pagedrop.transfer.ssh.KindleSsh

object DictionaryInstaller {
    private const val TAG = "DictInstaller"
    private const val KO_DICT_DIR = "/mnt/us/koreader/data/dict"

    /** File name endings a StarDict dictionary is made of. Anything else in the archive is skipped. */
    internal val STARDICT_SUFFIXES = listOf(
        ".ifo", ".idx", ".idx.gz", ".idx.dz", ".dict", ".dict.dz", ".syn", ".syn.dz", ".syn.gz",
    )
    /** Cap on what one archive may write (real bytes copied, not the sizes the tar headers claim). */
    internal const val MAX_EXTRACTED_BYTES = 1L shl 30 // 1 GiB
    /** Cap on tar entries of any kind, so a crafted archive can't loop for ever over empty entries. */
    internal const val MAX_ENTRIES = 1_000

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun downloadAndExtract(
        dict: DictionaryItem,
        cacheDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (dict.url.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Dictionary not available for download"))
            }

            val dictDir = File(cacheDir, dict.id)
            if (dictDir.exists() && dictDir.listFiles()?.isNotEmpty() == true) {
                return@withContext Result.success(dictDir)
            }
            dictDir.mkdirs()

            val archiveFile = File(cacheDir, "${dict.id}.tar.bz2")
            try {
                val req = Request.Builder().url(dict.url).build()
                val response = client.newCall(req).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        IllegalStateException("Download failed: ${response.code}")
                    )
                }
                response.body?.byteStream()?.use { input ->
                    archiveFile.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext Result.failure(IllegalStateException("Empty response"))
            } catch (e: Exception) {
                archiveFile.delete()
                return@withContext Result.failure(e)
            }

            // Extract tar.bz2 (flattened, StarDict files only, bounded; see extractStarDict)
            try {
                extractStarDict(archiveFile, dictDir)
            } catch (e: Exception) {
                dictDir.deleteRecursively()
                throw e
            } finally {
                archiveFile.delete()
            }

            // Validate: StarDict needs at least .ifo + .idx or .dict.dz
            val files = dictDir.listFiles()?.toList() ?: emptyList()
            val hasIfo = files.any { it.extension.equals("ifo", true) }
            val hasIdx = files.any { it.extension.equals("idx", true) }
            val hasDict = files.any {
                val name = it.name.lowercase()
                name.endsWith(".dict") || name.endsWith(".dict.dz")
            }

            if (!hasIfo || (!hasIdx && !hasDict)) {
                dictDir.deleteRecursively()
                return@withContext Result.failure(
                    IllegalStateException("Missing required StarDict files (need .ifo + .idx or .dict)")
                )
            }

            Result.success(dictDir)
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary download/extract failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Extracts the StarDict files of a tar.bz2 [archive] into [destDir], flattened to their base names.
     *
     * Only regular files with a [STARDICT_SUFFIXES] ending are written; directories are skipped, and symlinks,
     * hard links, devices and FIFOs are ignored. Every output must resolve (canonically) inside [destDir].
     * Throws when the archive has more than [maxEntries] entries, writes more than [maxBytes], or holds two
     * dictionary files with the same name.
     */
    internal fun extractStarDict(
        archive: File,
        destDir: File,
        maxBytes: Long = MAX_EXTRACTED_BYTES,
        maxEntries: Int = MAX_ENTRIES,
    ): List<File> {
        destDir.mkdirs()
        val written = mutableListOf<File>()
        var totalBytes = 0L
        var entries = 0
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                var entry: TarArchiveEntry? = tar.nextEntry
                while (entry != null) {
                    if (++entries > maxEntries) throw IllegalStateException("Dictionary archive has too many entries")
                    val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    // Regular files only: TarArchiveEntry.isFile is also true for symlinks, hard links and devices.
                    val regular = entry.linkFlag == TarConstants.LF_NORMAL || entry.linkFlag == TarConstants.LF_OLDNORM
                    val wanted = regular && !entry.isSymbolicLink && !entry.isLink &&
                        STARDICT_SUFFIXES.any { name.lowercase().endsWith(it) } &&
                        !name.startsWith(".")
                    if (wanted) {
                        val outFile = SafeFiles.childOf(destDir, name)
                        if (outFile.exists()) throw IllegalStateException("Dictionary archive repeats a file name")
                        outFile.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = tar.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                if (totalBytes > maxBytes) throw IllegalStateException("Dictionary archive is too large")
                                out.write(buffer, 0, read)
                            }
                        }
                        written += outFile
                    }
                    entry = tar.nextEntry
                }
            }
        }
        return written
    }

    suspend fun installToKoreader(
        dictDir: File,
        host: String,
        port: Int,
        user: String,
        pass: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            session = KindleSsh.connect(host, port, user, pass, 10_000)

            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)

            val uploaded = mutableListOf<String>()
            dictDir.listFiles()?.forEach { file ->
                if (file.isFile) {
                    val destPath = "$KO_DICT_DIR/${file.name}"
                    file.inputStream().use { sftp.put(it, destPath) }
                    uploaded.add(destPath)
                }
            }

            Log.d(TAG, "Dictionary installed: ${uploaded.size} files")
            Result.success(uploaded)
        } catch (e: Exception) {
            Log.e(TAG, "Dictionary install failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            sftp?.disconnect()
            session?.disconnect()
        }
    }
}
