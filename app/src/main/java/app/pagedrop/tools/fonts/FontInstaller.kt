package app.pagedrop.tools.fonts

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import app.pagedrop.transfer.ssh.KindleSsh

object FontInstaller {
    private const val TAG = "FontInstaller"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun downloadAndValidate(
        font: FontItem,
        cacheDir: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            if (font.url.isBlank()) {
                return@withContext Result.failure(IllegalStateException("Font not available for download"))
            }

            val ext = font.url.substringAfterLast(".", "ttf").lowercase()
            val destFile = File(cacheDir, "${font.id}.$ext")

            if (destFile.exists() && destFile.length() > 0) {
                return@withContext Result.success(destFile)
            }

            val req = Request.Builder().url(font.url).build()
            val response = client.newCall(req).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IllegalStateException("Download failed: ${response.code}")
                )
            }

            response.body?.byteStream()?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(IllegalStateException("Empty response"))

            if (destFile.length() < 1024) {
                destFile.delete()
                return@withContext Result.failure(IllegalStateException("Font file too small"))
            }

            // Validate: check magic bytes for TTF (0x00 0x01 0x00 0x00) or OTF (OTTO)
            val magic = destFile.inputStream().use { it.readNBytes(4) }
            val ttfMagic = byteArrayOf(0x00, 0x01, 0x00, 0x00)
            val otfMagic = "OTTO".toByteArray(Charsets.US_ASCII)
            val isZip = magic.size >= 2 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()

            if (isZip) {
                // Unzip and take the first font file
                val fontsDir = File(cacheDir, font.id)
                fontsDir.mkdirs()
                ZipInputStream(destFile.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    var extractedFont: File? = null
                    while (entry != null) {
                        val name = entry.name.lowercase()
                        if (name.endsWith(".ttf") || name.endsWith(".otf")) {
                            val outFile = File(fontsDir, File(entry.name).name)
                            outFile.outputStream().use { zis.copyTo(it) }
                            extractedFont = outFile
                            break
                        }
                        entry = zis.nextEntry
                    }
                    if (extractedFont != null) {
                        destFile.delete()
                        return@withContext Result.success(extractedFont)
                    }
                }
                return@withContext Result.failure(IllegalStateException("No font found in ZIP"))
            }

            if (magic.contentEquals(ttfMagic) || magic.contentEquals(otfMagic)) {
                return@withContext Result.success(destFile)
            }

            destFile.delete()
            Result.failure(IllegalStateException("Not a valid font file"))
        } catch (e: Exception) {
            Log.e(TAG, "Font download/validate failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun installToKindle(
        fontFile: File,
        targetPath: String,
        host: String,
        port: Int,
        user: String,
        pass: String
    ): Result<String> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftp: ChannelSftp? = null
        try {
            session = KindleSsh.connect(host, port, user, pass, 10_000)

            sftp = session.openChannel("sftp") as ChannelSftp
            sftp.connect(10_000)

            val destPath = if (targetPath.endsWith("/")) targetPath + fontFile.name
            else "$targetPath/${fontFile.name}"

            fontFile.inputStream().use { sftp.put(it, destPath) }
            Log.d(TAG, "Font installed: $destPath")
            Result.success(destPath)
        } catch (e: Exception) {
            Log.e(TAG, "Font install failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            sftp?.disconnect()
            session?.disconnect()
        }
    }
}
