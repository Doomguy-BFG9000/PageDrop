package app.pagedrop.server

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageDropApiClient @Inject constructor(
    private val serverSettings: ServerSettings,
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "PageDropApiClient"
        private const val POLL_INTERVAL_MS = 2000L
        private const val MAX_POLL_RETRIES = 45
    }

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    private val BINARY_MEDIA = "application/octet-stream".toMediaType()

    val isAvailable: Boolean get() = serverSettings.isConfigured

    private val baseUrl: String? get() = serverSettings.supabaseUrl.takeIf { it.isNotBlank() }
    private val anonKey: String? get() = serverSettings.supabaseAnonKey.takeIf { it.isNotBlank() }
    private val accessToken: String? get() = serverSettings.accessToken.takeIf { it.isNotBlank() }

    suspend fun testConnection(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext Result.failure(
                IllegalStateException("PageDrop conversion service is not configured in this build")
            )
            val key = anonKey ?: return@withContext Result.failure(
                IllegalStateException("PageDrop conversion service is not configured in this build")
            )
            val token = signInAnonymously().getOrElse { return@withContext Result.failure(it) }
            val req = Request.Builder()
                .url("$base/auth/v1/user")
                .header("apikey", key)
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            client.newCall(req).execute().use { response ->
                if (response.isSuccessful) Result.success(Unit)
                else Result.failure(IllegalStateException("PageDrop conversion service returned HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not reach the PageDrop conversion service: ${e.localizedMessage ?: "unknown error"}", e))
        }
    }

    suspend fun signInAnonymously(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("Server URL is not configured"))
            val key = anonKey ?: return@withContext Result.failure(IllegalStateException("Supabase anon key is not configured"))

            val existingToken = accessToken
            if (existingToken != null) return@withContext Result.success(existingToken)

            val body = buildJsonObject {
                put("data", buildJsonObject {
                    put("source", "pagedrop-android")
                })
            }.toString()

            val req = Request.Builder()
                .url("$base/auth/v1/signup")
                .header("apikey", key)
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(req).execute().use { response ->
                if (!response.isSuccessful) {
                    val responseBody = response.body?.string().orEmpty()
                    return@withContext Result.failure(
                        IllegalStateException(anonymousSignInErrorMessage(response.code, responseBody))
                    )
                }
                val responseBody = response.body?.string()
                    ?: return@withContext Result.failure(IllegalStateException("Empty auth response"))
                val parsed = json.parseToJsonElement(responseBody).jsonObject
                val sessionObject = parsed["session"]?.jsonObject
                val access = parsed["access_token"]?.jsonPrimitive?.content
                    ?: sessionObject?.get("access_token")?.jsonPrimitive?.content
                    ?: return@withContext Result.failure(IllegalStateException("Auth response did not include an access token"))
                val refresh = parsed["refresh_token"]?.jsonPrimitive?.content
                    ?: sessionObject?.get("refresh_token")?.jsonPrimitive?.content
                    ?: ""
                serverSettings.accessToken = access
                serverSettings.refreshToken = refresh
                Result.success(access)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Auth failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun createConvertJob(
        uri: Uri,
        targetFormat: String = "mobi",
        title: String? = null,
        author: String? = null
    ): JobResponse? = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext null
            val key = anonKey ?: return@withContext null
            val token = signInAnonymously().getOrNull() ?: return@withContext null

            val sourceBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            val fileName = safeFileName(
                getDisplayName(uri) ?: uri.lastPathSegment ?: "upload_${System.currentTimeMillis()}"
            )

            val body = buildJsonObject {
                put("jobType", "convert")
                put("targetFormat", targetFormat)
                put("fileName", fileName)
                title?.let { put("title", it) }
                author?.let { put("author", it) }
                put("fileSizeBytes", sourceBytes.size.toLong())
            }.toString()

            val job = createJob(base, key, token, body) ?: return@withContext null
            val sourcePath = job.sourceStoragePath ?: return@withContext null

            if (!uploadSource(base, key, token, sourcePath, sourceBytes)) return@withContext null
            if (!markJobPending(base, key, token, job.jobId)) return@withContext null

            job.copy(status = "pending")
        } catch (e: Exception) {
            Log.e(TAG, "Create job failed: ${e.message}", e)
            null
        }
    }

    suspend fun convertUriToLocalArtifact(
        uri: Uri,
        outputDir: File,
        targetFormat: String = "mobi",
        title: String? = null,
        author: String? = null
    ): PreparedArtifact? = withContext(Dispatchers.IO) {
        val job = createConvertJob(uri, targetFormat, title, author) ?: return@withContext null
        val status = pollJob(job.jobId) ?: return@withContext null
        if (status.status != "complete" || status.result_storage_path.isNullOrBlank()) return@withContext null

        outputDir.mkdirs()
        val format = status.target_format ?: targetFormat
        val artifactName = safeFileName(status.title ?: title ?: "converted_${System.currentTimeMillis()}") + ".$format"
        val artifactFile = uniqueFile(outputDir, artifactName)
        if (!downloadArtifact(status.result_storage_path, artifactFile)) return@withContext null

        val coverFile = status.cover_storage_path?.let { coverPath ->
            val target = uniqueFile(outputDir, artifactFile.nameWithoutExtension + "_cover.jpg")
            if (downloadArtifact(coverPath, target)) target else null
        }

        PreparedArtifact(
            file = artifactFile,
            coverFile = coverFile,
            title = status.title ?: title ?: artifactFile.nameWithoutExtension,
            author = status.author ?: author ?: "Unknown Author",
            format = format.uppercase()
        )
    }

    private fun createJob(base: String, key: String, token: String, body: String): JobResponse? {
        val req = Request.Builder()
            .url("$base/functions/v1/create-job")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) return null
            val responseBody = response.body?.string() ?: return null
            return json.decodeFromString<JobResponse>(responseBody)
        }
    }

    private fun uploadSource(base: String, key: String, token: String, sourcePath: String, sourceBytes: ByteArray): Boolean {
        val req = Request.Builder()
            .url("$base/storage/v1/object/sources/$sourcePath")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .header("x-upsert", "true")
            .post(sourceBytes.toRequestBody(BINARY_MEDIA))
            .build()
        client.newCall(req).execute().use { return it.isSuccessful }
    }

    private fun markJobPending(base: String, key: String, token: String, jobId: String): Boolean {
        val body = buildJsonObject {
            put("status", "pending")
        }.toString()
        val req = Request.Builder()
            .url("$base/rest/v1/jobs?id=eq.$jobId")
            .header("apikey", key)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .header("Prefer", "return=minimal")
            .patch(body.toRequestBody(JSON_MEDIA))
            .build()
        client.newCall(req).execute().use { return it.isSuccessful }
    }

    private fun safeFileName(name: String): String {
        val cleaned = name.substringAfterLast('/').substringAfterLast(':')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
        return cleaned.ifBlank { "pagedrop_${UUID.randomUUID()}" }
    }

    private fun getDisplayName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val baseName = name.substringBeforeLast(".")
        val ext = name.substringAfterLast(".", "")
        var counter = 1
        while (file.exists()) {
            file = File(dir, if (ext.isBlank()) "${baseName}_$counter" else "${baseName}_$counter.$ext")
            counter++
        }
        return file
    }

    data class PreparedArtifact(
        val file: File,
        val coverFile: File?,
        val title: String,
        val author: String,
        val format: String
    )

    suspend fun createArticleJob(url: String): JobResponse? = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext null
            val key = anonKey ?: return@withContext null
            val token = signInAnonymously().getOrNull() ?: return@withContext null

            val body = buildJsonObject {
                put("jobType", "article")
                put("url", url)
                put("targetFormat", "mobi")
            }.toString()

            createJob(base, key, token, body)
        } catch (e: Exception) {
            Log.e(TAG, "Create article job failed: ${e.message}", e)
            null
        }
    }

    suspend fun convertArticleToLocalArtifact(
        url: String,
        outputDir: File,
        targetFormat: String = "mobi"
    ): PreparedArtifact? = withContext(Dispatchers.IO) {
        val job = createArticleJob(url) ?: return@withContext null
        val status = pollJob(job.jobId) ?: return@withContext null
        if (status.status != "complete" || status.result_storage_path.isNullOrBlank()) return@withContext null

        outputDir.mkdirs()
        val format = status.target_format ?: targetFormat
        val artifactName = safeFileName(status.title ?: "article_${System.currentTimeMillis()}") + ".$format"
        val artifactFile = uniqueFile(outputDir, artifactName)
        if (!downloadArtifact(status.result_storage_path, artifactFile)) return@withContext null

        val coverFile = status.cover_storage_path?.let { coverPath ->
            val target = uniqueFile(outputDir, artifactFile.nameWithoutExtension + "_cover.jpg")
            if (downloadArtifact(coverPath, target)) target else null
        }

        PreparedArtifact(
            file = artifactFile,
            coverFile = coverFile,
            title = status.title ?: artifactFile.nameWithoutExtension,
            author = status.author ?: "Unknown Author",
            format = format.uppercase()
        )
    }

    suspend fun pollJob(jobId: String): JobStatus? = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext null
            val key = anonKey ?: return@withContext null
            val token = signInAnonymously().getOrNull() ?: return@withContext null
            var retries = 0

            while (retries < MAX_POLL_RETRIES) {
                delay(POLL_INTERVAL_MS)
                val url = "$base/rest/v1/jobs?id=eq.$jobId&select=*"
                val req = Request.Builder()
                    .url(url)
                    .header("apikey", key)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                val response = client.newCall(req).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: continue
                    val results = json.decodeFromString<List<JobStatus>>(body)
                    val status = results.firstOrNull() ?: continue
                    if (status.status in listOf("complete", "failed")) return@withContext status
                }
                retries++
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.message}", e)
            null
        }
    }

    suspend fun downloadArtifact(storagePath: String, outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext false
            val key = anonKey ?: return@withContext false
            val token = signInAnonymously().getOrNull() ?: return@withContext false

            val url = "$base/storage/v1/object/artifacts/$storagePath"
            val req = Request.Builder()
                .url(url)
                .header("apikey", key)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val response = client.newCall(req).execute()
            if (!response.isSuccessful) return@withContext false
            response.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            false
        }
    }

    suspend fun shouldUseServer(format: String): Boolean {
        if (!serverSettings.isConfigured) return false
        val nativeFormats = setOf("AZW3", "MOBI", "PRC")
        return format.uppercase() !in nativeFormats
    }
}

internal fun anonymousSignInErrorMessage(statusCode: Int, responseBody: String): String {
    val error = runCatching {
        Json.parseToJsonElement(responseBody).jsonObject
    }.getOrNull()
    val code = error?.get("error_code")?.jsonPrimitive?.contentOrNull
        ?: error?.get("code")?.jsonPrimitive?.contentOrNull
    val message = error?.get("msg")?.jsonPrimitive?.contentOrNull
        ?: error?.get("message")?.jsonPrimitive?.contentOrNull

    if (code == "anonymous_provider_disabled") {
        return "PageDrop server setup is incomplete. In Supabase, enable Authentication > Settings > Allow anonymous sign-ins."
    }

    return buildString {
        append("Anonymous sign-in failed: HTTP $statusCode")
        message?.takeIf { it.isNotBlank() }?.let { append(" ($it)") }
    }
}
