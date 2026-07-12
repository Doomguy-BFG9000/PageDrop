package app.pagedrop.transfer.sftp

import android.util.Log
import app.pagedrop.data.local.database.Book
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

            val destinationDirectories = directories
                .map { it.trim().removeSuffix("/") }
                .filter { it.isNotBlank() }
                .distinct()
            if (destinationDirectories.isEmpty()) {
                throw IllegalArgumentException("Add at least one Kindle destination path")
            }

            books.forEachIndexed { index, book ->
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
                    "if initctl status framework 2>/dev/null | grep -q 'start/running'; then " +
                        "if lipc-probe -l 2>/dev/null | grep -q 'com.lab126.amznAssetsMgrService'; then " +
                        "lipc-set-prop com.lab126.amznAssetsMgrService reScan 1; " +
                        "else lipc-set-prop -i com.lab126.indexer updateIndexerState 1; fi; " +
                        "fi"
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
}
