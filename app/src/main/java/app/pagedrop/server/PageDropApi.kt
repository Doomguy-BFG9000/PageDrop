package app.pagedrop.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class JobResponse(
    val jobId: String,
    val status: String,
    val sourceStoragePath: String? = null,
    val inputCoverStoragePath: String? = null,
)

@Serializable
data class JobStatus(
    val id: String,
    val status: String,
    val job_type: String? = null,
    val target_format: String? = null,
    val target_profile: String? = null,
    val result_storage_path: String? = null,
    val cover_storage_path: String? = null,
    val title: String? = null,
    val author: String? = null,
    val warnings: JsonElement? = null,
    val logs: JsonElement? = null,
    val error_message: String? = null,
    val created_at: String? = null,
    val completed_at: String? = null
)

@Serializable
data class SessionResponse(
    val access_token: String,
    val refresh_token: String? = null,
    val token_type: String? = null,
    val expires_in: Long? = null
)
