package app.pagedrop.server

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSettings internal constructor(
    private val prefs: SharedPreferences,
    private val builtInUrl: String = BuildConfig.PAGEDROP_SUPABASE_URL,
    private val builtInAnonKey: String = BuildConfig.PAGEDROP_SUPABASE_ANON_KEY,
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("pagedrop_server", Context.MODE_PRIVATE))

    val supabaseUrl: String
        get() = builtInUrl.trim().removeSuffix("/")

    val supabaseAnonKey: String
        get() = builtInAnonKey.trim()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

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

    val isAuthenticated: Boolean
        get() = accessToken.isNotBlank()
}
