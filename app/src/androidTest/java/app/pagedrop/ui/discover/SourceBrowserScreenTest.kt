package app.pagedrop.ui.discover

import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.pagedrop.discover.BookResult
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android WebView -> download callback -> review sheet, without third-party services or CAPTCHAs. */
@RunWith(AndroidJUnit4::class)
class SourceBrowserScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun englishBookLinkIsCapturedAndReviewedInsideApp() = checkDownload("Frankenstein", "Mary Shelley", false)
    @Test fun hindiAttachmentWithoutExtensionUsesDownloadListenerAndSession() = checkDownload("गोदान", "प्रेमचंद", true)

    private fun checkDownload(title: String, author: String, attachment: Boolean) {
        val selected = mutableStateOf<BookResult?>(null)
        val sendRequested = AtomicBoolean(false)
        val filename = "$title -- $author -- 1818 -- abcdef -- Anna’s Archive.epub"
        val downloadUrl = "https://files.example/".toHttpUrl().newBuilder()
            .addPathSegment(if (attachment) "fetch" else filename).build().toString()
        rule.setContent {
            MaterialTheme {
                SourceBrowserScreen(SourceBrowserTarget("Test library", "https://catalog.example/edition"), {}, { selected.value = it })
                selected.value?.let { result ->
                    ResultSheet(result, ActionState.Idle, result.options.single(), null, {},
                        onSend = { sendRequested.set(true) }, onAdd = {}, onRetry = {},
                        onEditMetadata = { t, a -> selected.value = result.copy(title = t, author = a) },
                        onOpenWebsite = {}, onDismiss = { selected.value = null })
                }
            }
        }
        lateinit var browser: WebView
        rule.runOnIdle {
            browser = findWebView(rule.activity.window.decorView)!!
            browser.stopLoading()
            val productionClient = browser.webViewClient
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    productionClient.shouldOverrideUrlLoading(view, request)
                override fun onPageFinished(view: WebView, url: String) = productionClient.onPageFinished(view, url)
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.url.host == "files.example") return WebResourceResponse(
                        "application/epub+zip", null, 200, "OK",
                        mapOf("Content-Disposition" to "attachment; filename=\"$filename\""), ByteArrayInputStream(byteArrayOf(80, 75)),
                    )
                    return null
                }
            }
            browser.loadDataWithBaseURL("https://catalog.example/edition",
                "<html><body><a id='download' href='$downloadUrl'>Download book</a></body></html>", "text/html", "UTF-8", "https://catalog.example/edition")
        }
        val ready = AtomicBoolean(false)
        rule.waitUntil(10_000) {
            rule.runOnIdle {
                browser.evaluateJavascript("!!document.getElementById('download')") { ready.set(it == "true") }
            }
            ready.get()
        }
        val cookieSet = CountDownLatch(1)
        rule.runOnIdle { CookieManager.getInstance().setCookie("https://files.example/", "browser_test=verified; Secure; Path=/") { cookieSet.countDown() } }
        assertTrue(cookieSet.await(3, TimeUnit.SECONDS))
        try {
            rule.runOnIdle { browser.evaluateJavascript("document.getElementById('download').click()", null) }
            rule.waitUntil(10_000) { selected.value != null }
            val result = selected.value!!
            assertEquals(title, result.title)
            assertEquals(author, result.author)
            val session = result.options.single().browserSession!!
            assertEquals("https://catalog.example/edition", session.referer)
            assertTrue(session.userAgent.contains("Mozilla"))
            assertEquals("verified", session.cookieJar.loadForRequest(downloadUrl.toHttpUrl()).single { it.name == "browser_test" }.value)
            assertTrue(session.cookieJar.loadForRequest("https://another.example/".toHttpUrl()).none { it.name == "browser_test" })
            rule.onNodeWithText("Review book details").assertExists()
            rule.onNodeWithText("Send to Kindle").performScrollTo().performClick()
            assertTrue(sendRequested.get())
        } finally {
            rule.runOnIdle { CookieManager.getInstance().setCookie("https://files.example/", "browser_test=; Max-Age=0; Secure; Path=/") }
        }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }
}
