package app.pagedrop.transfer.sftp

import android.util.Log
import app.pagedrop.data.local.database.Book
import app.pagedrop.tools.dashboard.DeviceCommandRunner
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

object KindleSftpClient {
    private const val TAG = "KindleSftpClient"
    private const val THUMBNAILS_DIR = "/mnt/us/system/thumbnails"

    internal fun kindleFileName(fileName: String): String =
        fileName.replace(Regex("\\.azw3$", RegexOption.IGNORE_CASE), ".azw")

    /** Folder the stock Kindle home screen indexes. */
    internal const val KINDLE_HOME_DIR = "/mnt/us/documents"

    /** KOReader's book folder on this device. */
    internal const val KOREADER_BOOKS_DIR = "/mnt/us/Books"

    private val STOCK_READER_FORMATS = setOf("MOBI", "AZW", "AZW3", "PRC", "PDF", "TXT")

    /**
     * Every book goes to the KOReader folder; formats the stock reader can open also go to
     * the home-screen folder, so they can be read without launching KOReader.
     */
    internal fun destinationsFor(book: Book, configured: List<String>): List<String> {
        val dirs = configured.map { it.trim().removeSuffix("/") }.filter { it.isNotBlank() }.toMutableList()
        if (book.format.uppercase() in STOCK_READER_FORMATS) dirs += KINDLE_HOME_DIR
        else dirs -= KINDLE_HOME_DIR
        dirs += KOREADER_BOOKS_DIR
        return dirs.distinct()
    }

    /**
     * Tests connection to the Kindle SSH/SFTP server.
     */
    suspend fun testConnection(
        host: String,
        port: Int,
        user: String,
        pass: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var session: Session? = null
        try {
            if (host.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("IP address cannot be empty"))
            }
            Log.d(TAG, "Testing SSH connection to $user@$host:$port")
            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(5000)
            Log.d(TAG, "SSH Connection test successful")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "SSH Connection test failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            session?.disconnect()
        }
    }

    /**
     * Transfers selected books to the Kindle via SFTP, then triggers a library rescan.
     *
     * @param thumbnailBytes optional pre-rendered thumbnail per book
     */
    suspend fun transferBooks(
        books: List<Book>,
        host: String,
        port: Int,
        user: String,
        pass: String,
        directories: List<String>,
        triggerRescan: Boolean,
        thumbnailBytes: Map<Int, ByteArray> = emptyMap(),
        onProgress: (Int, Int, String) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            if (host.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("IP address cannot be empty"))
            }

            onProgress(0, books.size, "Connecting to Kindle...")
            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)

            onProgress(0, books.size, "Opening SFTP channel...")
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)

            val createdDirectories = mutableSetOf<String>()

            books.forEachIndexed { index, book ->
                val destinationDirectories = destinationsFor(book, directories)
                destinationDirectories.filter { createdDirectories.add(it) }.forEach { dir ->
                    runCatching { sftpChannel.stat(dir) }.onFailure { sftpChannel.mkdir(dir) }
                }
                onProgress(index, books.size, "Sending: ${book.title}")
                val file = File(book.filePath)
                if (!file.exists()) {
                    throw java.io.FileNotFoundException("File not found: ${book.filePath}")
                }
                // This KOReader branch registers the KF8 provider as `.azw`, but not `.azw3`.
                // The payload is unchanged; the compatible extension lets its file browser list it.
                val remoteFileName = kindleFileName(book.fileName)

                destinationDirectories.forEach { destinationDirectory ->
                    val remotePath = "$destinationDirectory/$remoteFileName"
                    file.inputStream().use { inputStream ->
                        sftpChannel.put(inputStream, remotePath)
                    }

                    val remoteSize = sftpChannel.stat(remotePath).size
                    if (remoteSize != file.length()) {
                        throw IllegalStateException(
                            "Kindle could not verify $remoteFileName in $destinationDirectory"
                        )
                    }
                }

                thumbnailBytes[book.uid]?.let { tBytes ->
                    val thumbName = book.kindleUuid?.let {
                        "thumbnail_${it}_EBOK_portrait.jpg"
                    } ?: "thumbnail_${book.uid}_EBOK_portrait.jpg"
                    try {
                        ByteArrayInputStream(tBytes).use { tStream ->
                            sftpChannel.put(tStream, "$THUMBNAILS_DIR/$thumbName")
                        }
                        Log.d(TAG, "Thumbnail uploaded: $thumbName")
                    } catch (e: Exception) {
                        Log.w(TAG, "Thumbnail upload failed for ${book.title}: ${e.message}")
                    }
                }
            }

            onProgress(books.size, books.size, "Finalizing files on Kindle...")
            val syncChannel = session.openChannel("exec") as ChannelExec
            syncChannel.setCommand("sync")
            syncChannel.connect()
            var syncElapsed = 0
            while (!syncChannel.isClosed && syncElapsed < 5000) {
                Thread.sleep(100)
                syncElapsed += 100
            }
            if (!syncChannel.isClosed || syncChannel.exitStatus != 0) {
                syncChannel.disconnect()
                throw IllegalStateException("Kindle did not finish saving the transferred files")
            }
            syncChannel.disconnect()

            if (triggerRescan) {
                onProgress(books.size, books.size, "Requesting Kindle library refresh...")
                val execChannel = session.openChannel("exec") as ChannelExec
                execChannel.setCommand(
                    // Firmware 5.x rescans documents/ on a simulated resume; shared fallback chain tries all methods.
                    DeviceCommandRunner.RESCAN_FALLBACK_SCRIPT + "; true"
                )
                execChannel.connect()
                var elapsed = 0
                while (!execChannel.isClosed && elapsed < 2000) {
                    Thread.sleep(100)
                    elapsed += 100
                }
                execChannel.disconnect()
            }

            onProgress(books.size, books.size, "Files copied and verified on Kindle.")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "SFTP transfer failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
            session?.disconnect()
        }
    }

    /** Downloads [remotePath] from the Kindle into [target] over SFTP (read-only on the Kindle). */
    suspend fun downloadFile(
        host: String,
        port: Int,
        user: String,
        pass: String,
        remotePath: String,
        target: File
    ): Result<File> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var sftpChannel: ChannelSftp? = null
        try {
            val jsch = JSch()
            session = jsch.getSession(user, host, port)
            session.setPassword(pass)
            session.setConfig("StrictHostKeyChecking", "no")
            session.connect(10000)
            sftpChannel = session.openChannel("sftp") as ChannelSftp
            sftpChannel.connect(10000)
            target.parentFile?.mkdirs()
            val expected = sftpChannel.stat(remotePath).size
            target.outputStream().use { out -> sftpChannel.get(remotePath, out) }
            if (target.length() != expected) {
                target.delete()
                throw IllegalStateException("The file didn't copy completely.")
            }
            Result.success(target)
        } catch (e: Exception) {
            Log.e(TAG, "SFTP download failed: ${e.message}", e)
            target.delete()
            Result.failure(e)
        } finally {
            sftpChannel?.disconnect()
            session?.disconnect()
        }
    }
}
