package app.pagedrop.ui.discover

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.pagedrop.discover.BookResult
import app.pagedrop.discover.BrowserDownloadSession
import app.pagedrop.discover.BrowserDownloads
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class SourceBrowserTarget(val name: String, val url: String)

/** User-operated verification stays in WebView. Only a book download enters the native import flow. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun SourceBrowserScreen(
    target: SourceBrowserTarget,
    onClose: () -> Unit,
    onDownload: (BookResult) -> Unit,
) {
    var webView by remember(target) { mutableStateOf<WebView?>(null) }
    val savedState = rememberSaveable(target.url) { Bundle() }
    var host by remember(target) { mutableStateOf(target.url.toHttpUrlOrNull()?.host.orEmpty()) }
    var pageUrl by remember(target) { mutableStateOf(target.url) }
    var progress by remember(target) { mutableIntStateOf(0) }
    var message by remember(target) { mutableStateOf<String?>(null) }
    val currentDownload by rememberUpdatedState(onDownload)

    fun capture(view: WebView, url: String, ua: String?, disposition: String?, mime: String?, size: Long) {
        val cookies = CookieManager.getInstance()
        val session = BrowserDownloadSession(
            userAgent = ua?.takeIf { it.isNotBlank() } ?: view.settings.userAgentString,
            referer = view.url?.takeIf { it.toHttpUrlOrNull()?.isHttps == true } ?: pageUrl,
            readCookies = { cookies.getCookie(it) },
            writeCookie = { address, cookie -> cookies.setCookie(address, cookie) },
        )
        val result = BrowserDownloads.capture(url, disposition, mime, target.name, session, size)
        if (result != null) {
            progress = 100
            currentDownload(result)
        } else {
            message = "Choose an EPUB, MOBI, AZW3, PDF or TXT download."
        }
    }

    fun back() {
        val view = webView
        if (view?.canGoBack() == true) view.goBack() else onClose()
    }
    BackHandler(onBack = ::back)
    DisposableEffect(target) {
        onDispose {
            webView?.let { view ->
                view.saveState(savedState)
                view.stopLoading()
                view.setDownloadListener(null)
                view.destroy()
            }
            webView = null
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back in source") }
            Column(Modifier.weight(1f)) {
                Text(target.name, style = MaterialTheme.typography.titleMedium)
                Text(host, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = { webView?.reload() }) { Icon(Icons.Outlined.Refresh, "Reload source") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close source") }
        }
        Text(
            "Choose a book and tap its download link. Complete any website verification here.",
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
        )
        if (progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        message?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        AndroidView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            factory = { context ->
                WebView(context).apply {
                    webView = this
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = false
                        allowContentAccess = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        safeBrowsingEnabled = true
                        javaScriptCanOpenWindowsAutomatically = false
                        // Links targeting a new window open in this same browser.
                        setSupportMultipleWindows(false)
                        builtInZoomControls = true
                        displayZoomControls = false
                    }
                    CookieManager.getInstance().setAcceptCookie(true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            if (!request.isForMainFrame) return false
                            val address = request.url.toString()
                            if (address.toHttpUrlOrNull()?.isHttps != true) {
                                message = "This link can't be opened here. Use a secure website download."
                                return true
                            }
                            if (BrowserDownloads.isBookLink(address)) {
                                capture(view, address, null, null, null, -1)
                                return true
                            }
                            return false
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            url.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let {
                                host = it.host
                                pageUrl = url
                            }
                            message = null
                        }

                        override fun onPageFinished(view: WebView, url: String) { view.saveState(savedState) }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) message = "Couldn't load this page. Check your connection and tap Reload."
                        }
                    }
                    setDownloadListener { url, ua, disposition, mime, length -> capture(this, url, ua, disposition, mime, length) }
                    if (target.url.toHttpUrlOrNull()?.isHttps != true) {
                        message = "Use a secure website address to browse for books."
                        progress = 100
                    } else if (savedState.isEmpty || restoreState(savedState) == null) loadUrl(target.url)
                }
            },
        )
    }
}
