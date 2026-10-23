package app.pagedrop.server

import android.content.ContextWrapper
import app.pagedrop.data.NullSharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

/** Every upload/submission re-reads the server switch and the notice answer right before it goes out. */
class ServerConsentRecheckTest {
    private val server = MockWebServer()
    private val paths = CopyOnWriteArrayList<String>()
    private lateinit var settings: ServerSettings
    private lateinit var consent: ServerUploadConsent
    private lateinit var api: PageDropApiClient

    /** Called on the server thread for each request, before the response is chosen. */
    @Volatile private var onRequest: (String) -> Unit = {}
    @Volatile private var jobStatusJson: String = """[{"id":"j1","status":"complete","target_format":"mobi","result_storage_path":"u/j1/out.mobi"}]"""
    @Volatile private var signupHangs: Boolean = false

    private val jwt: String = run {
        val enc = Base64.getUrlEncoder().withoutPadding()
        enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString("""{"exp":9999999999}""".toByteArray()) + ".sig"
    }

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                paths += path
                onRequest(path)
                return when {
                    path.endsWith("/auth/v1/signup") && signupHangs -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                    path.endsWith("/auth/v1/signup") ->
                        MockResponse().setBody("""{"access_token":"$jwt","refresh_token":"r"}""")
                    path.endsWith("/functions/v1/create-job") -> MockResponse().setBody("""{"jobId":"j1","status":"created"}""")
                    path.endsWith("/rest/v1/jobs") -> MockResponse().setBody(jobStatusJson)
                    path.contains("/storage/v1/object/artifacts/") -> MockResponse().setBody("MOBI-BYTES")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        settings = ServerSettings(NullSharedPreferences(), server.url("/").toString(), "anon")
        consent = ServerUploadConsent(settings)
        api = PageDropApiClient(settings, consent, ContextWrapper(null))
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun nothingIsSentBeforeTheNoticeIsAnswered() = runBlocking {
        assertNull(api.createArticleJob("https://example.com/a"))
        assertTrue(paths.isEmpty())
    }

    @Test
    fun nothingIsSentWhenTheServerWasSwitchedOffAfterAgreeing() = runBlocking {
        consent.answer(true)
        settings.useServer = false
        assertNull(api.createArticleJob("https://example.com/a"))
        assertTrue(paths.isEmpty())
    }

    @Test
    fun articleIsSubmittedWhenAgreedAndOn() = runBlocking {
        consent.answer(true)
        assertNotNull(api.createArticleJob("https://example.com/a"))
        assertEquals(listOf("/auth/v1/signup", "/functions/v1/create-job"), paths.toList())
    }

    @Test
    fun switchingOffBetweenSignInAndSubmissionStopsTheSubmission() = runBlocking {
        consent.answer(true)
        onRequest = { if (it.endsWith("/auth/v1/signup")) settings.useServer = false }
        assertNull(api.createArticleJob("https://example.com/a"))
        assertEquals(listOf("/auth/v1/signup"), paths.toList())
    }

    @Test
    fun turningTheServerOffCancelsARunningRequest() = runBlocking {
        consent.answer(true)
        signupHangs = true
        val job = async(Dispatchers.IO) { api.createArticleJob("https://example.com/a") }
        // Wait until the sign-in request has reached the server, then switch the server off.
        withTimeout(5_000) { while (paths.isEmpty()) kotlinx.coroutines.delay(20) }
        val started = System.currentTimeMillis()
        consent.answer(false)
        assertNull(withTimeout(5_000) { job.await() })
        assertTrue("cancel should not wait for the slow response", System.currentTimeMillis() - started < 5_000)
        assertFalse(paths.any { it.endsWith("/functions/v1/create-job") })
    }

    @Test
    fun aResultInAFormatTheAppDoesNotOfferIsIgnored() = runBlocking {
        consent.answer(true)
        jobStatusJson = """[{"id":"j1","status":"complete","target_format":"../../shared_prefs/x","result_storage_path":"u/j1/out"}]"""
        val out = java.nio.file.Files.createTempDirectory("articles").toFile()
        assertNull(api.convertArticleToLocalArtifact("https://example.com/a", out))
        assertFalse(paths.any { it.contains("/storage/v1/object/artifacts/") })
        assertTrue(out.list()!!.isEmpty())
    }

    @Test
    fun anOfferedResultFormatIsSavedInsideTheOutputDirectory() = runBlocking {
        consent.answer(true)
        jobStatusJson = """[{"id":"j1","status":"complete","target_format":"MOBI","title":"../../evil","result_storage_path":"u/j1/out.mobi"}]"""
        val out = java.nio.file.Files.createTempDirectory("articles2").toFile()
        val prepared = api.convertArticleToLocalArtifact("https://example.com/a", out)
        assertNotNull(prepared)
        assertEquals("MOBI", prepared!!.format)
        assertEquals(out.canonicalFile, prepared.file.canonicalFile.parentFile)
        assertTrue(prepared.file.name.endsWith(".mobi"))
    }

    @Test
    fun requestingAFormatTheAppDoesNotOfferSendsNothing() = runBlocking {
        consent.answer(true)
        val uri = android.net.TestUri()
        assertNull(api.createConvertJob(uri, targetFormat = "exe"))
        assertTrue(paths.isEmpty())
    }

    @Test
    fun serverFormatAllowlist() {
        assertEquals("mobi", ServerFormats.normalize("MOBI"))
        assertEquals("kepub", ServerFormats.normalize(" kepub "))
        assertNull(ServerFormats.normalize("../mobi"))
        assertNull(ServerFormats.normalize("sh"))
        assertNull(ServerFormats.normalize(null))
    }
}
