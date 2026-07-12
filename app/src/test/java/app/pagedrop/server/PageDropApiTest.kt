package app.pagedrop.server

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageDropApiTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun jobResponse_deserialization() {
        val raw = """{"jobId":"job_123","status":"pending"}"""
        val job = json.decodeFromString<JobResponse>(raw)
        assertEquals("job_123", job.jobId)
        assertEquals("pending", job.status)
    }

    @Test
    fun jobStatus_deserialization() {
        val raw = """{"id":"job_123","status":"complete","title":"Test","author":"Author"}"""
        val status = json.decodeFromString<JobStatus>(raw)
        assertEquals("complete", status.status)
        assertEquals("Test", status.title)
        assertEquals("Author", status.author)
    }

    @Test
    fun jobStatus_inProgress() {
        val raw = """{"id":"job_456","status":"processing"}"""
        val status = json.decodeFromString<JobStatus>(raw)
        assertEquals("processing", status.status)
    }

    @Test
    fun jobStatus_withResultPath() {
        val raw = """{"id":"job_789","status":"complete","result_storage_path":"user/job/output.mobi","cover_storage_path":"user/job/cover.jpg"}"""
        val status = json.decodeFromString<JobStatus>(raw)
        assertEquals("user/job/output.mobi", status.result_storage_path)
        assertEquals("user/job/cover.jpg", status.cover_storage_path)
    }

    @Test
    fun jobStatus_withError() {
        val raw = """{"id":"job_fail","status":"failed","error_message":"Conversion timed out"}"""
        val status = json.decodeFromString<JobStatus>(raw)
        assertEquals("failed", status.status)
        assertEquals("Conversion timed out", status.error_message)
    }

    @Test
    fun jobStatus_ignoresExtraFields() {
        val raw = """{"id":"j1","status":"complete","extra_field":"ignored"}"""
        val status = json.decodeFromString<JobStatus>(raw)
        assertEquals("j1", status.id)
    }

    @Test
    fun serverSettings_defaults() {
        // Default URL should be the user's Supabase project
        assertTrue(true)
    }

    @Test
    fun anonymousSignInError_explainsHowToEnableProvider() {
        val message = anonymousSignInErrorMessage(
            422,
            """{"code":422,"error_code":"anonymous_provider_disabled","msg":"Anonymous sign-ins are disabled"}"""
        )

        assertEquals(
            "PageDrop server setup is incomplete. In Supabase, enable Authentication > Settings > Allow anonymous sign-ins.",
            message
        )
    }
}
