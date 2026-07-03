package app.pagedrop.server

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("pagedrop_server", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(value) = prefs.edit().putBoolean("enabled", value).apply()

    var supabaseUrl: String
        get() = prefs.getString("supabase_url", "") ?: ""
        set(value) = prefs.edit().putString("supabase_url", value).apply()

    var supabaseAnonKey: String
        get() = prefs.getString("supabase_anon_key", "") ?: ""
        set(value) = prefs.edit().putString("supabase_anon_key", value).apply()

    var accessToken: String
        get() = prefs.getString("access_token", "") ?: ""
        set(value) = prefs.edit().putString("access_token", value).apply()

    var refreshToken: String
        get() = prefs.getString("refresh_token", "") ?: ""
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    val isConfigured: Boolean
        get() = enabled && supabaseUrl.isNotBlank() && supabaseAnonKey.isNotBlank()

    val isAuthenticated: Boolean
        get() = accessToken.isNotBlank()
}
