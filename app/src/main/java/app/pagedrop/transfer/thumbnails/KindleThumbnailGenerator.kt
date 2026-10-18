package app.pagedrop.transfer.thumbnails

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import app.pagedrop.transfer.ssh.KindleSsh

object KindleThumbnailGenerator {
    private const val TAG = "KindleThumbnailGen"

    private const val THUMBNAIL_WIDTH = 330
    private const val THUMBNAIL_HEIGHT = 430
    private const val JPEG_QUALITY = 85
    private const val THUMBNAILS_DIR = "/mnt/us/system/thumbnails"

    fun thumbnailName(kindleUuid: String): String =
        "thumbnail_${kindleUuid}_EBOK_portrait.jpg"

    suspend fun transferThumbnail(
        bookId: Int,
        kindleUuid: String?,
        coverPath: String?,
        coverBytes: ByteArray?,
        host: String,
        port: Int,
        user: String,
        pass: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val thumbnailBytes = when {
                coverBytes != null && coverBytes.isNotEmpty() -> resizeToThumbnail(coverBytes)
                coverPath != null -> {
                    val file = File(coverPath)
                    if (file.exists()) resizeToThumbnail(file.readBytes())
                    else throw IllegalStateException("Cover file not found: $coverPath")
                }
                else -> throw IllegalStateException("No cover available for book $bookId")
            }

            if (kindleUuid.isNullOrBlank()) {
                throw IllegalStateException("No Kindle UUID for book $bookId")
            }

            val name = thumbnailName(kindleUuid)

            var session: Session? = null
            var sftp: ChannelSftp? = null
            try {
                session = KindleSsh.connect(host, port, user, pass, 10_000)

                sftp = session.openChannel("sftp") as ChannelSftp
                sftp.connect(10_000)

                thumbnailBytes.inputStream().use { stream ->
                    sftp.put(stream, "$THUMBNAILS_DIR/$name")
                }

                Log.d(TAG, "Thumbnail uploaded: $name")
            } finally {
                sftp?.disconnect()
                session?.disconnect()
            }

            Result.success(name)
        } catch (e: Exception) {
            Log.e(TAG, "Thumbnail transfer failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    private fun resizeToThumbnail(bytes: ByteArray): ByteArray {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalStateException("Failed to decode cover image")

        val scaled = Bitmap.createScaledBitmap(
            source,
            THUMBNAIL_WIDTH,
            THUMBNAIL_HEIGHT,
            true
        )

        if (scaled != source) source.recycle()

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        scaled.recycle()

        return out.toByteArray()
    }
}
