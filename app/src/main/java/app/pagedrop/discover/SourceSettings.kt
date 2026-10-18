package app.pagedrop.discover

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.security.KeystoreSecretStore
import app.pagedrop.security.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which book sources are searched, and the user's own OPDS catalogs. No catalog is ever pre-filled.
 * Passwords live in [KeystoreSecretStore]; everything else in private preferences.
 */
@Singleton
class SourceSettings internal constructor(
    private val prefs: SharedPreferences,
    private val secrets: SecretStore,
    private val http: DiscoverHttp,
) {
    @Inject
    constructor(@ApplicationContext ctx: Context) : this(
        ctx.getSharedPreferences("pagedrop_discover", Context.MODE_PRIVATE),
        KeystoreSecretStore(ctx.getSharedPreferences("pagedrop_discover_secrets", Context.MODE_PRIVATE), "pagedrop_opds_credentials"),
        OkHttpDiscoverHttp(),
    )

    private val _enabledBuiltIns = MutableStateFlow(loadBuiltIns())
    val enabledBuiltIns: StateFlow<Set<SourceKind>> = _enabledBuiltIns.asStateFlow()

    private val _catalogs = MutableStateFlow(loadCatalogs())
    val catalogs: StateFlow<List<OpdsCatalog>> = _catalogs.asStateFlow()

    private val _showShadowLibraries = MutableStateFlow(prefs.getBoolean(KEY_SHADOW_LIBRARIES, false))
    /** Whether Anna's Archive and OceanofPDF website shortcuts are listed under search results. Off by default. */
    val showShadowLibraries: StateFlow<Boolean> = _showShadowLibraries.asStateFlow()

    private val lock = Any()

    fun setShowShadowLibraries(on: Boolean) {
        prefs.edit().putBoolean(KEY_SHADOW_LIBRARIES, on).apply()
        _showShadowLibraries.value = on
    }

    fun setEnabled(kind: SourceKind, on: Boolean) {
        if (!kind.isBuiltIn) return // catalogs and browser downloads are not search switches
        synchronized(lock) {
            val next = if (on) _enabledBuiltIns.value + kind else _enabledBuiltIns.value - kind
            prefs.edit().putStringSet(KEY_BUILTINS, next.map { it.name }.toSet())
                .putStringSet(KEY_KNOWN_BUILTINS, DEFAULT_BUILTINS.map { it.name }.toSet()).apply()
            _enabledBuiltIns.value = next
        }
    }

    /** Validates by fetching the catalog's root feed, then stores it. Failure messages are written for end users. */
    suspend fun addCatalog(url: String, username: String?, password: String?): Result<OpdsCatalog> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(addCatalogBlocking(url, username, password))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SourceUnavailableException) {
                Result.failure(e)
            } catch (e: java.net.UnknownHostException) {
                Result.failure(SourceUnavailableException("Can't reach that address. Check the URL and your connection.", offline = true, cause = e))
            } catch (e: java.io.IOException) {
                Result.failure(SourceUnavailableException("Couldn't connect to that catalog.", cause = e))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun addCatalogBlocking(rawUrl: String, rawUser: String?, rawPassword: String?): OpdsCatalog {
        val url = normalizeUrl(rawUrl) ?: throw SourceUnavailableException("Enter a catalog address like https://example.org/opds")
        val user = rawUser?.trim()?.takeIf { it.isNotEmpty() }
        val password = rawPassword?.takeIf { user != null && it.isNotEmpty() }
        if (user != null && !url.startsWith("https://")) {
            throw SourceUnavailableException("Use an https:// address when signing in, so your password isn't sent in the clear.")
        }
        val id = catalogId(url)
        if (_catalogs.value.any { it.id == id }) throw SourceUnavailableException("That catalog is already added.")

        val auth = if (user != null) BasicAuth(user, password.orEmpty()) else null
        val reply = http.get(url, auth, "application/atom+xml, application/xml;q=0.9, */*;q=0.5")
        if (reply.code == 401 || reply.code == 403) {
            throw SourceUnavailableException(
                if (auth == null) "This catalog needs a username and password." else "Sign-in was rejected. Check the username and password.",
            )
        }
        if (!reply.ok) throw SourceUnavailableException("The catalog answered with an error (HTTP ${reply.code}).")
        val feed = OpdsParser.parseFeed(reply.body, reply.finalUrl)
        val title = feed.title?.takeIf { it.isNotBlank() } ?: url.toHttpUrlOrNull()?.host ?: "OPDS catalog"

        if (password != null) {
            try {
                secrets.put(passwordKey(id), password)
            } catch (e: Exception) {
                throw SourceUnavailableException("Couldn't store the password securely on this device.", cause = e)
            }
        }
        val catalog = OpdsCatalog(id, title, url, user, enabled = true)
        synchronized(lock) { saveCatalogs(_catalogs.value + catalog) }
        return catalog
    }

    fun removeCatalog(id: String) {
        synchronized(lock) {
            runCatching { secrets.remove(passwordKey(id)) }
            saveCatalogs(_catalogs.value.filterNot { it.id == id })
        }
    }

    fun setCatalogEnabled(id: String, on: Boolean) {
        synchronized(lock) { saveCatalogs(_catalogs.value.map { if (it.id == id) it.copy(enabled = on) else it }) }
    }

    /**
     * Credentials for one catalog, or null. When [forUrl] is given they are released only if it is on the catalog's
     * own host, so a catalog entry can never make PageDrop send the password to a third-party site.
     */
    fun credentialsFor(id: String, forUrl: String? = null): BasicAuth? {
        val catalog = _catalogs.value.firstOrNull { it.id == id } ?: return null
        if (forUrl != null && forUrl.toHttpUrlOrNull()?.host != catalog.url.toHttpUrlOrNull()?.host) return null
        val user = catalog.username ?: return null
        val password = secrets.get(passwordKey(id)) ?: return null
        return BasicAuth(user, password)
    }

    private fun passwordKey(id: String) = "pw_$id"

    private fun loadBuiltIns(): Set<SourceKind> {
        val stored = prefs.getStringSet(KEY_BUILTINS, null) ?: return DEFAULT_BUILTINS
        val enabled = stored.mapNotNull { runCatching { SourceKind.valueOf(it) }.getOrNull() }
            .filter { it.isBuiltIn }.toSet()
        val known = prefs.getStringSet(KEY_KNOWN_BUILTINS, null) ?: setOf("GUTENBERG", "STANDARD_EBOOKS", "INTERNET_ARCHIVE")
        // Add new built-ins once on upgrade, while preserving switches and an explicit "all off" choice.
        val migrated = if (enabled.isEmpty()) enabled else enabled + DEFAULT_BUILTINS.filter { it.name !in known }
        prefs.edit().putStringSet(KEY_BUILTINS, migrated.map { it.name }.toSet())
            .putStringSet(KEY_KNOWN_BUILTINS, DEFAULT_BUILTINS.map { it.name }.toSet()).apply()
        return migrated
    }

    private fun loadCatalogs(): List<OpdsCatalog> = try {
        val raw = prefs.getString(KEY_CATALOGS, null) ?: "[]"
        Json.parseToJsonElement(raw).jsonArray.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            OpdsCatalog(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                url = o["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                username = o["username"]?.jsonPrimitive?.contentOrNull,
                enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveCatalogs(list: List<OpdsCatalog>) {
        val json = buildJsonArray {
            list.forEach { c ->
                add(buildJsonObject {
                    put("id", c.id); put("title", c.title); put("url", c.url); put("enabled", c.enabled)
                    c.username?.let { put("username", it) }
                })
            }
        }.toString()
        prefs.edit().putString(KEY_CATALOGS, json).apply()
        _catalogs.value = list
    }

    internal companion object {
        private const val KEY_BUILTINS = "enabled_builtins"
        private const val KEY_KNOWN_BUILTINS = "known_builtins"
        private const val KEY_CATALOGS = "catalogs"
        private const val KEY_SHADOW_LIBRARIES = "show_shadow_libraries"
        val DEFAULT_BUILTINS = SourceKind.entries.filter { it.isBuiltIn }.toSet()

        fun normalizeUrl(raw: String): String? {
            var s = raw.trim()
            if (s.isEmpty() || s.contains(' ')) return null
            if (!s.contains("://")) s = "https://$s"
            val parsed = s.toHttpUrlOrNull() ?: return null
            return parsed.toString()
        }

        fun catalogId(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.lowercase().trimEnd('/').toByteArray())
                .take(5).joinToString("") { "%02x".format(it) }
    }
}
