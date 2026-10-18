package app.pagedrop.server

import app.pagedrop.data.NullSharedPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUploadConsentTest {
    private fun settings(url: String = "https://example.supabase.co", key: String = "anon") =
        ServerSettings(NullSharedPreferences(), url, key)

    @Test
    fun useServerStaysOnByDefault() {
        assertTrue(settings().useServer)
        assertFalse(settings().uploadNoticeAnswered)
    }

    @Test
    fun firstUploadWaitsForTheNoticeThenUsesTheServer() = runTest {
        val s = settings()
        val consent = ServerUploadConsent(s)
        val first = async { consent.confirm() }
        val second = async { consent.confirm() }
        runCurrent()
        assertTrue(consent.pending.value)
        assertFalse(first.isCompleted)

        consent.answer(true)
        assertTrue(first.await())
        assertTrue(second.await())
        assertFalse(consent.pending.value)
        assertTrue(s.uploadNoticeAnswered)
        // Remembered: later uploads don't ask again.
        assertTrue(consent.confirm())
        assertFalse(consent.pending.value)
    }

    @Test
    fun keepOnPhoneTurnsTheServerOffForGood() = runTest {
        val s = settings()
        val consent = ServerUploadConsent(s)
        val upload = async { consent.confirm() }
        runCurrent()
        consent.answer(false)
        assertFalse(upload.await())
        assertFalse(s.useServer)
        assertFalse(consent.confirm())
        assertFalse(consent.pending.value)
    }

    @Test
    fun noNoticeWhenTheServerIsOffOrMissing() = runTest {
        val off = settings().apply { useServer = false }
        assertFalse(ServerUploadConsent(off).confirm())
        val missing = ServerUploadConsent(settings(url = "", key = ""))
        assertFalse(missing.confirm())
        assertFalse(missing.pending.value)
    }

    @Test
    fun answeringFromSettingsAlsoCountsAsAnswered() = runTest {
        val s = settings()
        val consent = ServerUploadConsent(s)
        consent.answer(true)
        assertTrue(consent.confirm())
        assertEquals(true, s.uploadNoticeAnswered)
    }
}
