package app.pagedrop.discover

import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

const val DISCOVER_USER_AGENT = "PageDrop/0.7.0 (+https://github.com/spandan-kumar/PageDrop)"

data class BasicAuth(val username: String, val password: String)

data class HttpReply(val code: Int, val body: String, val finalUrl: String, val contentType: String?) {
    val ok: Boolean get() = code in 200..299
}

/** A response whose body is still open; close it. */
class HttpStream(
    val code: Int,
    val contentLength: Long,
    val contentType: String?,
    val finalUrl: String,
    val input: InputStream,
    private val onClose: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching { input.close() }
        onClose()
    }
}

/** Blocking HTTP seam so every parser/engine/downloader is testable with canned responses. Never call on Main. */
interface DiscoverHttp {
    fun get(url: String, auth: BasicAuth? = null, accept: String? = null): HttpReply
    fun open(url: String, auth: BasicAuth? = null): HttpStream
    fun openBrowser(url: String, session: BrowserDownloadSession): HttpStream =
        throw IOException("Browser downloads are not supported by this connection")
    fun postForm(url: String, fields: Map<String, String>): HttpReply =
        throw SourceUnavailableException("This source's search is not supported")
    /** Bound synchronous network I/O too; coroutine interruption alone cannot reliably stop a socket read. */
    fun withTimeout(timeoutMs: Long): DiscoverHttp = this
}

@Singleton
class OkHttpDiscoverHttp internal constructor(
    private val requestTimeoutMs: Long,
    private val httpClient: OkHttpClient = client,
) : DiscoverHttp {
    @Inject constructor() : this(20_000L)

    override fun withTimeout(timeoutMs: Long): DiscoverHttp = OkHttpDiscoverHttp(timeoutMs, httpClient)

    override fun get(url: String, auth: BasicAuth?, accept: String?): HttpReply {
        return execute(request(url, auth, accept))
    }

    override fun postForm(url: String, fields: Map<String, String>): HttpReply {
        val form = FormBody.Builder().apply { fields.forEach { (key, value) -> add(key, value) } }.build()
        return execute(request(url, null, "application/json").newBuilder().post(form).build())
    }

    private fun execute(request: Request): HttpReply {
        httpClient.newBuilder().callTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS).build().newCall(request).execute().use { response ->
            val body = response.body ?: return HttpReply(response.code, "", request.url.toString(), null)
            val text = readCapped(body.byteStream(), MAX_TEXT_BYTES)
            return HttpReply(response.code, text, response.request.url.toString(), body.contentType()?.toString())
        }
    }

    override fun open(url: String, auth: BasicAuth?): HttpStream {
        return openStream(httpClient.newBuilder().readTimeout(30, TimeUnit.SECONDS).build(), request(url, auth, "*/*"))
    }

    override fun openBrowser(url: String, session: BrowserDownloadSession): HttpStream {
        val req = request(url, null, "*/*")
        if (!req.url.isHttps) throw IOException("Use a secure download address")
        val browserClient = httpClient.newBuilder()
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .cookieJar(session.cookieJar)
            .addNetworkInterceptor { chain ->
                val builder = chain.request().newBuilder().header("User-Agent", session.userAgent)
                session.refererFor(chain.request().url)?.let { builder.header("Referer", it) }
                chain.proceed(builder.build())
            }.build()
        return openStream(browserClient, req)
    }

    private fun openStream(httpClient: OkHttpClient, request: Request): HttpStream {
        val response = httpClient.newCall(request).execute()
        val body = response.body
        if (body == null) {
            response.close()
            throw IOException("Empty response")
        }
        return HttpStream(
            code = response.code,
            contentLength = body.contentLength(),
            contentType = body.contentType()?.toString(),
            finalUrl = response.request.url.toString(),
            input = body.byteStream(),
            onClose = { response.close() },
        )
    }

    private fun request(url: String, auth: BasicAuth?, accept: String?): Request =
        Request.Builder().url(secureUrl(url))
            .header("User-Agent", DISCOVER_USER_AGENT)
            .apply {
                if (accept != null) header("Accept", accept)
                // OkHttp drops this header itself when a redirect leaves the original host.
                if (auth != null) header("Authorization", Credentials.basic(auth.username, auth.password, Charsets.UTF_8))
            }
            .build()

    private fun readCapped(input: InputStream, max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("Response too large")
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_TEXT_BYTES = 8 * 1024 * 1024

        // OkHttp adds Accept-Encoding: gzip and transparently decompresses; redirects (incl. https) are followed.
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .cookieJar(FadedPageCookieJar())
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        }
    }
}

internal fun HttpReply.requireOk(what: String): HttpReply {
    if (!ok) throw SourceUnavailableException("$what answered with an error (HTTP $code)")
    return this
}

/**
 * Android blocks plain-HTTP requests for this app, so an `http://` address would only ever fail. Catalogs still
 * hand some out (Project Gutenberg's OpenSearch template is `http://m.gutenberg.org/...`), and those servers answer
 * on HTTPS, so the request goes there instead. Anything that isn't `http://` is returned unchanged.
 */
internal fun secureUrl(url: String): String =
    if (url.regionMatches(0, "http://", 0, 7, ignoreCase = true)) "https://" + url.substring(7) else url
