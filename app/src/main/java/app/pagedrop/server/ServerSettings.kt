package app.pagedrop.server

import android.content.Context
import app.pagedrop.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("pagedrop_server", Context.MODE_PRIVATE)

    val supabaseUrl: String
        get() = BuildConfig.PAGEDROP_SUPABASE_URL.trim().removeSuffix("/")

    val supabaseAnonKey: String
        get() = BuildConfig.PAGEDROP_SUPABASE_ANON_KEY.trim()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    val isConfigured: Boolean
        get() = supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    val isAuthenticated: Boolean
        get() = accessToken.isNotBlank()
}
