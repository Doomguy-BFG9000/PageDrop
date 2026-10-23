package app.pagedrop.server

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.BuildConfig
import app.pagedrop.security.KeystoreSecretStore
import app.pagedrop.security.MemorySecretStore
import app.pagedrop.security.SecretStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSettings internal constructor(
    private val prefs: SharedPreferences,
    private val builtInUrl: String = BuildConfig.PAGEDROP_SUPABASE_URL,
    private val builtInAnonKey: String = BuildConfig.PAGEDROP_SUPABASE_ANON_KEY,
    secretsFactory: () -> SecretStore = { MemorySecretStore() },
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        secretsFactory = { ServerSecrets.store(context) },
    )

    private val secrets: SecretStore by lazy(secretsFactory)

    /** Decrypted tokens, and tokens the Keystore refused to encrypt (those then last until the app closes). */
    private val memory = ConcurrentHashMap<String, String>()

    val supabaseUrl: String
        get() = builtInUrl.trim().removeSuffix("/")

    val supabaseAnonKey: String
        get() = builtInAnonKey.trim()

    /** Encrypted with a Keystore key ([ServerSecrets]); never newly written in plain text. */
    var accessToken: String
        get() = readToken(KEY_ACCESS_TOKEN)
        set(value) = writeToken(KEY_ACCESS_TOKEN, value)

    /** Encrypted with a Keystore key ([ServerSecrets]); never newly written in plain text. */
    var refreshToken: String
        get() = readToken(KEY_REFRESH_TOKEN)
        set(value) = writeToken(KEY_REFRESH_TOKEN, value)

    /** True when this build ships with a conversion server URL and key. */
    val hasBuiltInServer: Boolean
        get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    /** User preference; on by default so builds with a server keep their current behaviour. */
    var useServer: Boolean
        get() = prefs.getBoolean("use_server", true)
        set(value) = prefs.edit().putBoolean("use_server", value).apply()

    /** True once the user answered the one-time "books are uploaded for conversion" notice (either way). */
    var uploadNoticeAnswered: Boolean
        get() = prefs.getBoolean("upload_notice_answered", false)
        set(value) = prefs.edit().putBoolean("upload_notice_answered", value).apply()

    val isConfigured: Boolean
        get() = useServer && hasBuiltInServer

    /**
     * Read fresh right before anything is sent: the server is on and the upload notice was answered "use server"
     * (answering "keep on phone" switches [useServer] off).
     */
    val uploadAllowed: Boolean
        get() = isConfigured && uploadNoticeAnswered

    val isAuthenticated: Boolean
        get() = accessToken.isNotBlank()

    @Synchronized
    private fun readToken(key: String): String {
        migrateLegacyToken(key)
        memory[key]?.let { return it }
        secrets.get(key)?.let { memory[key] = it; return it }
        // A legacy plain value only survives while encryption keeps failing; it still beats "".
        return prefs.getString(key, null) ?: ""
    }

    @Synchronized
    private fun writeToken(key: String, value: String) {
        // Drop the legacy plain copy first so a later migration can never bring an old token back.
        if (prefs.contains(key)) prefs.edit().remove(key).commit()
        memory.remove(key)
        if (value.isEmpty()) {
            runCatching { secrets.remove(key) }
            return
        }
        try {
            secrets.put(key, value)
        } catch (e: Exception) {
            runCatching { secrets.remove(key) }
        }
        memory[key] = value
    }

    /**
     * Versions up to 0.9.3 kept both tokens in plain preferences. Move each into the encrypted store; the plain copy
     * is removed only after the encrypted one was written and read back, the same way KindleSettings migrates the
     * SSH password.
     */
    private fun migrateLegacyToken(key: String) {
        val legacy = prefs.getString(key, null) ?: return
        if (legacy.isNotEmpty()) {
            try {
                secrets.put(key, legacy)
                if (secrets.get(key) != legacy) return
            } catch (_: Exception) {
                return
            }
        }
        prefs.edit().remove(key).commit()
    }

    internal companion object {
        /** Must match the exclusions in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS = "pagedrop_server"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
    }
}

/** Where the conversion server's access and refresh tokens live (Keystore-encrypted). */
internal object ServerSecrets {
    /** Must match the exclusions in res/xml/backup_rules.xml and data_extraction_rules.xml. */
    const val PREFS = "pagedrop_server_secrets"
    private const val KEY_ALIAS = "pagedrop_server_tokens"

    fun store(context: Context): SecretStore =
        KeystoreSecretStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KEY_ALIAS)
}
