package app.pagedrop.server

import android.content.ContextWrapper
import app.pagedrop.data.NullSharedPreferences
import app.pagedrop.discover.BookSearchLink
import app.pagedrop.discover.BookWebSearch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArticleConsentWiringTest {
    private fun client(): Pair<PageDropApiClient, ServerUploadConsent> {
        val settings = ServerSettings(NullSharedPreferences(), "http://127.0.0.1:1", "anon")
        val consent = ServerUploadConsent(settings)
        return PageDropApiClient(settings, consent, ContextWrapper(null)) to consent
    }

    @Test
    fun articleLinkWaitsForTheNoticeBeforeAnyRequest() = runTest {
        val (api, consent) = client()
        val job = async { api.convertArticleWithConsent("https://example.com/a", File("/tmp")) }
        runCurrent()
        // Still waiting on the notice: no sign-in or job request can have started.
        assertTrue(consent.pending.value)
        assertFalse(job.isCompleted)
        consent.answer(false)
        assertNull(job.await())
    }

    @Test
    fun decliningSwitchesTheServerOffSoNothingIsSentAndCallersFallBackToThePhone() = runTest {
        val (api, consent) = client()
        consent.answer(false)
        assertFalse(api.isAvailable)
        assertFalse(api.confirmUpload())
        assertNull(api.convertArticleWithConsent("https://example.com/a", File("/tmp")))
    }

    @Test
    fun confirmUploadIsFalseWhenNotConfigured() = runTest {
        val settings = ServerSettings(NullSharedPreferences(), "", "")
        val api = PageDropApiClient(settings, ServerUploadConsent(settings), ContextWrapper(null))
        assertFalse(api.confirmUpload())
    }

    @Test
    fun shadowShortcutsAreHiddenAtRenderTimeWhenToggledOff() {
        val links = BookWebSearch.links("dune", includeShadowLibraries = true)
        val off = BookWebSearch.visibleLinks(links, showShadowLibraries = false).map(BookSearchLink::sourceName)
        assertEquals(listOf("ePustakalay", "Global Grey"), off)
        assertEquals(links, BookWebSearch.visibleLinks(links, showShadowLibraries = true))
    }
}
