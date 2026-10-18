package app.pagedrop.tools.screensavers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import app.pagedrop.transfer.ssh.KindleSsh

object ScreensaverProcessor {
    private const val TAG = "ScreensaverProc"
    private const val LINKSS_DIR = "/mnt/us/linkss/screensavers"
    private const val JPEG_QUALITY = 90

    data class ProcessingResult(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val originalWidth: Int,
        val originalHeight: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProcessingResult) return false
            return width == other.width && height == other.height &&
                    originalWidth == other.originalWidth &&
                    originalHeight == other.originalHeight &&
                    bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + width
            result = 31 * result + height
            result = 31 * result + originalWidth
            result = 31 * result + originalHeight
            return result
        }
    }

    suspend fun processImage(
        imageBytes: ByteArray,
        model: KindleModel
    ): ProcessingResult = withContext(Dispatchers.Default) {
        val source = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw IllegalArgumentException("Failed to decode image")

        val originalW = source.width
        val originalH = source.height

        // Step 1: Crop to Kindle aspect ratio (center-crop)
        val targetAspect = model.width.toFloat() / model.height.toFloat()
        val cropped = cropToAspect(source, targetAspect)

        // Step 2: Resize to exact Kindle resolution
        val resized = Bitmap.createScaledBitmap(cropped, model.width, model.height, true)
        if (cropped != source && cropped != resized) cropped.recycle()
        if (resized != cropped) { }

        // Step 3: Convert to grayscale
        val grayscale = Bitmap.createBitmap(model.width, model.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(grayscale)
        val paint = Paint()
        val cm = ColorMatrix()
        cm.setSaturation(0f)
        paint.colorFilter = ColorMatrixColorFilter(cm)
        canvas.drawBitmap(resized, 0f, 0f, paint)
        resized.recycle()

        // Step 4: Encode as JPEG
        val out = ByteArrayOutputStream()
        grayscale.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        grayscale.recycle()

        ProcessingResult(
            bytes = out.toByteArray(),
            width = model.width,
            height = model.height,
            originalWidth = originalW,
            originalHeight = originalH
        )
    }

    suspend fun uploadToKindle(
        imageBytes: ByteArray,
        fileName: String,
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

            imageBytes.inputStream().use { stream ->
                sftp.put(stream, "$LINKSS_DIR/$fileName")
            }

            Log.d(TAG, "Screensaver uploaded: $fileName")
            Result.success(fileName)
        } catch (e: Exception) {
            Log.e(TAG, "Screensaver upload failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            sftp?.disconnect()
            session?.disconnect()
        }
    }

    private fun cropToAspect(source: Bitmap, targetAspect: Float): Bitmap {
        val srcW = source.width.toFloat()
        val srcH = source.height.toFloat()
        val srcAspect = srcW / srcH

        if (kotlin.math.abs(srcAspect - targetAspect) < 0.01f) return source

        val cropW: Float
        val cropH: Float
        if (srcAspect > targetAspect) {
            // Source wider than target → crop width
            cropH = srcH
            cropW = srcH * targetAspect
        } else {
            // Source taller than target → crop height
            cropW = srcW
            cropH = srcW / targetAspect
        }

        val left = ((srcW - cropW) / 2f).toInt()
        val top = ((srcH - cropH) / 2f).toInt()

        return Bitmap.createBitmap(
            source,
            left.coerceAtLeast(0),
            top.coerceAtLeast(0),
            cropW.toInt().coerceAtMost(source.width - left),
            cropH.toInt().coerceAtMost(source.height - top)
        )
    }
}
