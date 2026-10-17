package app.pagedrop.discover

import java.io.ByteArrayInputStream
import java.io.File

internal fun fixture(name: String): String =
    checkNotNull(object {}.javaClass.getResourceAsStream("/discover/$name")) { "missing fixture $name" }
        .readBytes().toString(Charsets.UTF_8)

/** Canned HTTP: first matching substring wins. Records requests and the auth that was attached. */
internal class FakeHttp : DiscoverHttp {
    class Route(val match: String, val reply: () -> HttpReply)

    val routes = mutableListOf<Route>()
    val requests: MutableList<Pair<String, BasicAuth?>> = java.util.Collections.synchronizedList(mutableListOf())
    var streamBytes: Map<String, ByteArray> = emptyMap()
    var streamCode: Int = 200
    var streamContentLength: Long? = null
    val postedForms = mutableListOf<Pair<String, Map<String, String>>>()

    fun on(match: String, body: String, code: Int = 200, finalUrl: String? = null) {
        routes += Route(match) { HttpReply(code, body, finalUrl ?: match, null) }
    }

    fun fail(match: String, e: Exception) {
        routes += Route(match) { throw e }
    }

    override fun get(url: String, auth: BasicAuth?, accept: String?): HttpReply {
        requests += url to auth
        val route = routes.firstOrNull { url.contains(it.match) } ?: return HttpReply(404, "", url, null)
        return route.reply().let { if (it.finalUrl == route.match) it.copy(finalUrl = url) else it }
    }

    override fun open(url: String, auth: BasicAuth?): HttpStream {
        requests += url to auth
        val bytes = streamBytes.entries.firstOrNull { url.contains(it.key) }?.value ?: ByteArray(0)
        return HttpStream(streamCode, streamContentLength ?: bytes.size.toLong(), null, url, ByteArrayInputStream(bytes))
    }

    override fun postForm(url: String, fields: Map<String, String>): HttpReply {
        postedForms += url to fields
        return get(url)
    }
}

internal fun tempDir(prefix: String = "pagedrop-test"): File =
    java.nio.file.Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

internal fun newSettings(http: DiscoverHttp = FakeHttp(), secrets: SecretStore = MemorySecrets(), allSources: Boolean = false): SourceSettings =
    SourceSettings(app.pagedrop.data.NullSharedPreferences(), secrets, http).apply {
        // Existing fixtures cover the original three catalogs; new-source tests opt into the full registry.
        if (!allSources) SourceKind.entries.filter { it !in setOf(SourceKind.GUTENBERG, SourceKind.STANDARD_EBOOKS, SourceKind.INTERNET_ARCHIVE, SourceKind.OPDS) }
            .forEach { setEnabled(it, false) }
    }

internal class MemorySecrets : SecretStore {
    val map = mutableMapOf<String, String>()
    override fun get(key: String) = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}
