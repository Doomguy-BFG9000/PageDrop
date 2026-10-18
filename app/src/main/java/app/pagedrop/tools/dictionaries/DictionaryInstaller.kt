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
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import app.pagedrop.transfer.ssh.KindleSsh

object DictionaryInstaller {
    private const val TAG = "DictInstaller"
    private const val KO_DICT_DIR = "/mnt/us/koreader/data/dict"
    private val STARDICT_FILES = listOf(".ifo", ".idx", ".dz", ".dict")

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

            // Extract tar.bz2
            try {
                BZip2CompressorInputStream(archiveFile.inputStream()).use { bz2 ->
                    TarArchiveInputStream(bz2).use { tar ->
                        var entry: TarArchiveEntry? = tar.nextEntry
                        while (entry != null) {
                            val outFile = File(dictDir, entry.name)
                            if (!entry.isDirectory) {
                                outFile.parentFile?.mkdirs()
                                outFile.outputStream().use { tar.copyTo(it) }
                            }
                            entry = tar.nextEntry
                        }
                    }
                }
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
