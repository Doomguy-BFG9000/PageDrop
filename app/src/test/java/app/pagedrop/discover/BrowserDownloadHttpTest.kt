package app.pagedrop.discover

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BrowserDownloadHttpTest {
    @Test fun redirectUsesDestinationCookiesAndBrowserUaWithoutLeakingFullReferer() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { source -> MockWebServer().use { files ->
            source.useHttps(serverTls.sslSocketFactory(), false)
            files.useHttps(serverTls.sslSocketFactory(), false)
            source.start(); files.start()
            val destination = files.url("/file.epub").newBuilder().host("127.0.0.1").build()
            source.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination))
            files.enqueue(MockResponse().setBody("book bytes").addHeader("Set-Cookie", "fresh=destination; Secure; HttpOnly; Path=/"))
            val lookedUp = mutableListOf<String>()
            val saved = mutableListOf<Pair<String, String>>()
            val session = BrowserDownloadSession("Exact WebView UA", source.url("/slow_download/private?token=secret").toString(), { url ->
                lookedUp += url
                if (url.contains("127.0.0.1")) "download=destination" else "verified=source"
            }, { url, cookie -> saved += url to cookie })
            val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
            OkHttpDiscoverHttp(5_000, client).openBrowser(source.url("/begin").toString(), session).use {
                assertEquals("book bytes", it.input.bufferedReader().readText())
            }
            val first = source.takeRequest(2, TimeUnit.SECONDS)!!
            val second = files.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("verified=source", first.getHeader("Cookie"))
            assertEquals("download=destination", second.getHeader("Cookie"))
            assertEquals("Exact WebView UA", first.getHeader("User-Agent"))
            assertEquals("Exact WebView UA", second.getHeader("User-Agent"))
            assertTrue(first.getHeader("Referer")!!.contains("token=secret"))
            assertEquals(source.url("/").toString(), second.getHeader("Referer"))
            assertTrue(lookedUp.any { it.contains("127.0.0.1") })
            assertTrue(saved.any { it.first.startsWith("https://127.0.0.1") && it.second.contains("fresh=destination") })
        } }
    }
}
