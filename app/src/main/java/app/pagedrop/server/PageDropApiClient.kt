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
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import app.pagedrop.security.SafeFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PageDropApiClient @Inject constructor(
    private val serverSettings: ServerSettings,
    private val uploadConsent: ServerUploadConsent,
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

    /** Requests to the conversion server that are still running; cancelled when the user switches the server off. */
    private val inFlight: MutableSet<Call> = ConcurrentHashMap.newKeySet()

    init {
        uploadConsent.addTurnedOffListener(::cancelInFlight)
    }

    /** Runs [request], keeping it cancellable through [cancelInFlight] until [block] has read the response. */
    private inline fun <T> call(request: Request, block: (Response) -> T): T {
        val call = client.newCall(request)
        inFlight += call
        try {
            return call.execute().use(block)
        } finally {
            inFlight -= call
        }
    }

    /**
     * Cancels every running request to the conversion server. The cancelled calls fail with an IOException, so their
     * callers return null and fall back to on-phone conversion. Called when the server switch is turned off.
     */
    fun cancelInFlight() {
        val calls = inFlight.toList()
        if (calls.isNotEmpty()) Log.i(TAG, "Server switched off; cancelling ${calls.size} request(s)")
        calls.forEach { it.cancel() }
    }

    /**
     * Rechecked immediately before every upload or submission (and between polls): the server is still on and the
     * upload notice was answered "use server". False means stop; the caller falls back to on-phone conversion.
     */
    private fun stillAllowed(step: String): Boolean {
        if (serverSettings.uploadAllowed) return true
        Log.i(TAG, "Server use was switched off or not agreed to; not sending ($step)")
        return false
    }

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
            fun checkUser(token: String): Int {
                val req = Request.Builder()
                    .url("$base/auth/v1/user")
                    .header("apikey", key)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()
                return call(req) { it.code }
            }
            var code = checkUser(signInAnonymously().getOrElse { return@withContext Result.failure(it) })
            if (code == 401 || code == 403) {
                // Session was rejected before its expiry (revoked or user removed): start a fresh one once.
                serverSettings.accessToken = ""
                serverSettings.refreshToken = ""
                code = checkUser(signInAnonymously().getOrElse { return@withContext Result.failure(it) })
            }
            if (code in 200..299) Result.success(Unit)
            else Result.failure(IllegalStateException("PageDrop conversion service returned HTTP $code"))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("Could not reach the PageDrop conversion service: ${e.localizedMessage ?: "unknown error"}", e))
        }
    }

    suspend fun signInAnonymously(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val base = baseUrl ?: return@withContext Result.failure(IllegalStateException("Server URL is not configured"))
            val key = anonKey ?: return@withContext Result.failure(IllegalStateException("Supabase anon key is not configured"))

            val existingToken = accessToken
            if (existingToken != null && !isJwtExpired(existingToken)) {
                return@withContext Result.success(existingToken)
            }
            // Access tokens expire after about an hour; refresh before falling back to a new anonymous user.
            refreshSession(base, key)?.let { return@withContext Result.success(it) }
            serverSettings.accessToken = ""
            serverSettings.refreshToken = ""

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

            call(req) { response ->
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
            Log.e(TAG, "Auth failed: ${e.javaClass.simpleName}")
            Result.failure(e)
        }
    }

    /** Exchanges the stored refresh token for a new session; null when there is none or it was rejected. */
    private fun refreshSession(base: String, key: String): String? {
        val refresh = serverSettings.refreshToken.takeIf { it.isNotBlank() } ?: return null
        val body = buildJsonObject { put("refresh_token", refresh) }.toString()
        val req = Request.Builder()
            .url("$base/auth/v1/token?grant_type=refresh_token")
            .header("apikey", key)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA))
            .build()
        return runCatching {
            call(req) { response ->
                if (!response.isSuccessful) return null
                val parsed = json.parseToJsonElement(response.body?.string() ?: return null).jsonObject
                val access = parsed["access_token"]?.jsonPrimitive?.contentOrNull ?: return null
                serverSettings.accessToken = access
                parsed["refresh_token"]?.jsonPrimitive?.contentOrNull?.let { serverSettings.refreshToken = it }
                access
            }
        }.onFailure { Log.w(TAG, "Session refresh failed: ${it.javaClass.simpleName}") }.getOrNull()
    }

    suspend fun createConvertJob(
        uri: Uri,
        targetFormat: String = "mobi",
        title: String? = null,
        author: String? = null,
        coverUri: Uri? = null,
        coverUrl: String? = null,
    ): JobResponse? = withContext(Dispatchers.IO) {
        try {
            if (ServerFormats.normalize(targetFormat) == null) {
                Log.w(TAG, "Refusing a server conversion to a format the app does not offer")
                return@withContext null
            }
            if (!stillAllowed("sign-in")) return@withContext null
            val base = baseUrl ?: return@withContext null
            val key = anonKey ?: return@withContext null
            val token = signInAnonymously().getOrNull() ?: return@withContext null

            val sourceBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext null
            val fileName = safeFileName(
                getDisplayName(uri) ?: uri.lastPathSegment ?: "upload_${System.currentTimeMillis()}"
            )
            val coverBytes = when {
                coverUri != null -> context.contentResolver.openInputStream(coverUri)?.use { it.readBytes() }
                !coverUrl.isNullOrBlank() -> downloadBytes(coverUrl)
                else -> null
            }
            val coverFileName = when {
                coverUri != null -> safeFileName(getDisplayName(coverUri) ?: "cover.jpg")
                coverBytes != null -> "google_books_cover.jpg"
                else -> null
            }

            val body = buildJsonObject {
                put("jobType", "convert")
                put("targetFormat", targetFormat)
                put("fileName", fileName)
                title?.let { put("title", it) }
                author?.let { put("author", it) }
                coverFileName?.let { put("coverFileName", it) }
                coverBytes?.let { put("coverSizeBytes", it.size.toLong()) }
                put("fileSizeBytes", sourceBytes.size.toLong())
            }.toString()

            // The switch can be turned off while the file is read or the session is fetched: check again before
            // each request that sends something. A job left unmarked is never picked up by the server.
            if (!stillAllowed("create job")) return@withContext null
            val job = createJob(base, key, token, body) ?: return@withContext null
            val sourcePath = job.sourceStoragePath ?: return@withContext null

            if (!stillAllowed("book upload")) return@withContext null
            if (!uploadSource(base, key, token, sourcePath, sourceBytes)) return@withContext null
            if (coverBytes != null && job.inputCoverStoragePath != null) {
                if (!stillAllowed("cover upload")) return@withContext null
                if (!uploadSource(base, key, token, job.inputCoverStoragePath, coverBytes)) return@withContext null
            }
            if (!stillAllowed("start job")) return@withContext null
            if (!markJobPending(base, key, token, job.jobId)) return@withContext null

            job.copy(status = "pending")
        } catch (e: Exception) {
            Log.e(TAG, "Create job failed: ${e.javaClass.simpleName}")
            null
        }
    }

    suspend fun convertUriToLocalArtifact(
        uri: Uri,
        outputDir: File,
        targetFormat: String = "mobi",
        title: String? = null,
        author: String? = null,
        coverUri: Uri? = null,
        coverUrl: String? = null,
    ): PreparedArtifact? = withContext(Dispatchers.IO) {
        val job = createConvertJob(uri, targetFormat, title, author, coverUri, coverUrl) ?: return@withContext null
        val status = pollJob(job.jobId) ?: return@withContext null
        if (status.status == "failed") {
            throw IllegalStateException(status.error_message ?: "PageDrop server conversion failed")
        }
        if (status.status != "complete" || status.result_storage_path.isNullOrBlank()) return@withContext null
        if (!stillAllowed("download result")) return@withContext null

        outputDir.mkdirs()
        val format = acceptedResultFormat(status.target_format, targetFormat) ?: return@withContext null
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

        call(req) { response ->
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
        return call(req) { it.isSuccessful }
    }

    private fun downloadBytes(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.bytes()
        }
    }.getOrNull()

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
        return call(req) { it.isSuccessful }
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

    /** A new file in [dir]; throws if [name] would resolve (canonically) anywhere outside it. */
    private fun uniqueFile(dir: File, name: String): File = SafeFiles.uniqueChild(dir, name)

    /**
     * The format to save a finished job as: what the server reports, or what was asked for, but only when it is one
     * of the formats the app offers ([ServerFormats]). Anything else is refused (null) and the caller falls back.
     */
    private fun acceptedResultFormat(reported: String?, requested: String): String? {
        val format = ServerFormats.normalize(reported ?: requested)
        if (format == null) Log.w(TAG, "Server returned an unexpected output format; ignoring the result")
        return format
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
            if (!stillAllowed("sign-in")) return@withContext null
            val base = baseUrl ?: return@withContext null
            val key = anonKey ?: return@withContext null
            val token = signInAnonymously().getOrNull() ?: return@withContext null

            val body = buildJsonObject {
                put("jobType", "article")
                put("url", url)
                put("targetFormat", "mobi")
            }.toString()

            if (!stillAllowed("article submission")) return@withContext null
            createJob(base, key, token, body)
        } catch (e: Exception) {
            Log.e(TAG, "Create article job failed: ${e.javaClass.simpleName}")
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
        if (!stillAllowed("download article")) return@withContext null

        outputDir.mkdirs()
        val format = acceptedResultFormat(status.target_format, targetFormat) ?: return@withContext null
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
                if (!stillAllowed("poll")) return@withContext null
                val url = "$base/rest/v1/jobs?id=eq.$jobId&select=*"
                val req = Request.Builder()
                    .url(url)
                    .header("apikey", key)
                    .header("Authorization", "Bearer $token")
                    .get()
                    .build()

                val done = call(req) { response ->
                    if (!response.isSuccessful) return@call null
                    val body = response.body?.string() ?: return@call null
                    json.decodeFromString<List<JobStatus>>(body).firstOrNull()
                        ?.takeIf { it.status in listOf("complete", "failed") }
                }
                if (done != null) return@withContext done
                retries++
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "Poll failed: ${e.javaClass.simpleName}")
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

            call(req) { response ->
                if (!response.isSuccessful) return@call false
                val body = response.body ?: return@call false
                body.byteStream().use { input ->
                    outputFile.outputStream().use { output -> input.copyTo(output) }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.javaClass.simpleName}")
            outputFile.delete()
            false
        }
    }

    /** True once the user has answered the first-upload notice (either way). */
    val uploadNoticeAnswered: Boolean get() = serverSettings.uploadNoticeAnswered

    /**
     * Whether anything (book, cover or article link) may be sent to the server. Call before any network request,
     * including [testConnection]; shows the first-upload notice if it hasn't been answered. False when the server
     * is off or not configured.
     */
    suspend fun confirmUpload(): Boolean = uploadConsent.confirm()

    /**
     * Sends [url] to the server for extraction only after [confirmUpload]; null (nothing sent) when the user keeps
     * things on the phone, so callers fall back to on-phone extraction.
     */
    suspend fun convertArticleWithConsent(url: String, outputDir: File): PreparedArtifact? {
        if (!confirmUpload()) return null
        return convertArticleToLocalArtifact(url, outputDir)
    }

    suspend fun shouldUseServer(format: String): Boolean {
        if (!serverSettings.isConfigured) return false
        val nativeFormats = setOf("AZW3", "MOBI", "PRC")
        if (format.uppercase() in nativeFormats) return false
        // Before the first book upload the user is told what is sent and may choose on-phone conversion instead.
        return uploadConsent.confirm()
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

/** True when the JWT's `exp` claim is missing, unreadable, or within [skewSeconds] of now. */
internal fun isJwtExpired(token: String, nowSeconds: Long = System.currentTimeMillis() / 1000, skewSeconds: Long = 60): Boolean {
    val payload = token.split('.').getOrNull(1) ?: return true
    return runCatching {
        val decoded = String(java.util.Base64.getUrlDecoder().decode(payload.padEnd((payload.length + 3) / 4 * 4, '=')))
        val exp = Json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.contentOrNull?.toLong() ?: return true
        exp - skewSeconds <= nowSeconds
    }.getOrDefault(true)
}
