package app.pagedrop.transfer.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import app.pagedrop.PageDrop
import app.pagedrop.data.local.database.AppDatabase
import app.pagedrop.data.local.database.BookDao
import app.pagedrop.data.KindleSettings
import app.pagedrop.transfer.sftp.KindleSftpClient
import dagger.hilt.android.EntryPointAccessors
import java.io.ByteArrayOutputStream
import java.io.File

class TransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "TransferWorker"
        const val KEY_BOOK_IDS = "book_ids"
    }

    // Resolve dependencies via Hilt entry point
    private val database = EntryPointAccessors.fromApplication(
        appContext, DatabaseEntryPoint::class.java
    ).appDatabase()

    private val kindleSettings = EntryPointAccessors.fromApplication(
        appContext, SettingsEntryPoint::class.java
    ).kindleSettings()

    private val bookDao = database.bookDao()

    override suspend fun doWork(): Result {
        val bookIds = inputData.getIntArray(KEY_BOOK_IDS)?.toList() ?: return Result.failure()
        val books = bookIds.mapNotNull { bookDao.getBookById(it) }
        if (books.isEmpty()) return Result.failure()

        setForeground(createForegroundInfo("Preparing..."))

        try {
            val thumbs = prepareThumbnails(books)
            val host = kindleSettings.host
            val port = kindleSettings.port
            val user = kindleSettings.username
            val pass = kindleSettings.password
            val directories = kindleSettings.targetDirectories
            val rescan = kindleSettings.triggerRescan

            val result = KindleSftpClient.transferBooks(
                books = books,
                host = host, port = port, user = user, pass = pass,
                directories = directories, triggerRescan = rescan,
                thumbnailBytes = thumbs
            ) { _: Int, _: Int, _: String -> }

            if (result.isSuccess) {
                val timestamp = System.currentTimeMillis()
                books.forEach { bookDao.updateLastTransferred(it.uid, timestamp) }
                val notification = NotificationHelper.buildCompleteNotification(applicationContext, books.size)
                NotificationManagerCompat.from(applicationContext).notify(
                    NotificationHelper.COMPLETE_NOTIFICATION_ID, notification.build()
                )
                return Result.success()
            } else {
                val error = result.exceptionOrNull()?.localizedMessage ?: "Unknown error"
                val notification = NotificationHelper.buildErrorNotification(applicationContext, error, books.firstOrNull()?.title ?: "")
                NotificationManagerCompat.from(applicationContext).notify(
                    NotificationHelper.PROGRESS_NOTIFICATION_ID, notification.build()
                )
                return Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Transfer failed: ${e.message}", e)
            val notification = NotificationHelper.buildErrorNotification(applicationContext, e.localizedMessage ?: "Error", "")
            NotificationManagerCompat.from(applicationContext).notify(
                NotificationHelper.PROGRESS_NOTIFICATION_ID, notification.build()
            )
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationHelper.buildProgressNotification(applicationContext, 0, 1, message).build()
        return ForegroundInfo(NotificationHelper.PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun prepareThumbnails(books: List<app.pagedrop.data.local.database.Book>): Map<Int, ByteArray> {
        val thumbs = mutableMapOf<Int, ByteArray>()
        books.forEach { book ->
            book.coverPath?.let { path ->
                try {
                    val file = File(path)
                    if (file.exists()) {
                        val source = BitmapFactory.decodeFile(path) ?: return@let
                        val scaled = Bitmap.createScaledBitmap(source, 330, 430, true)
                        if (scaled != source) source.recycle()
                        val out = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        scaled.recycle()
                        thumbs[book.uid] = out.toByteArray()
                    }
                } catch (_: Exception) { }
            }
        }
        return thumbs
    }
}
