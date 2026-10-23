package app.pagedrop.server

import app.pagedrop.data.NullSharedPreferences
import app.pagedrop.security.MemorySecretStore
import app.pagedrop.security.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerSettingsTokenTest {
    private val prefs = NullSharedPreferences()

    /** A Keystore that refuses to encrypt (put throws) but otherwise behaves. */
    private class BrokenStore : SecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = throw IllegalStateException("no keystore")
        override fun remove(key: String) {}
    }

    private fun settings(secrets: SecretStore) = ServerSettings(prefs, "https://x.supabase.co", "anon") { secrets }

    @Test
    fun tokensAreKeptInTheSecretStoreNotPreferences() {
        val secrets = MemorySecretStore()
        val s = settings(secrets)
        s.accessToken = "access-1"
        s.refreshToken = "refresh-1"

        assertEquals("access-1", s.accessToken)
        assertEquals("refresh-1", s.refreshToken)
        assertEquals("access-1", secrets.get("access_token"))
        assertEquals("refresh-1", secrets.get("refresh_token"))
        assertFalse(prefs.all.values.contains("access-1"))
        assertFalse(prefs.all.values.contains("refresh-1"))
        assertTrue(s.isAuthenticated)

        s.accessToken = ""
        s.refreshToken = ""
        assertEquals("", s.accessToken)
        assertNull(secrets.get("access_token"))
        assertNull(secrets.get("refresh_token"))
    }

    @Test
    fun legacyPlainTextTokensAreMovedAndErased() {
        prefs.edit().putString("access_token", "old-access").putString("refresh_token", "old-refresh").apply()
        val secrets = MemorySecretStore()
        val s = settings(secrets)

        assertEquals("old-access", s.accessToken)
        assertEquals("old-refresh", s.refreshToken)
        assertFalse(prefs.contains("access_token"))
        assertFalse(prefs.contains("refresh_token"))
        assertEquals("old-access", secrets.get("access_token"))
        assertEquals("old-refresh", secrets.get("refresh_token"))
    }

    @Test
    fun keystoreFailure_keepsLegacyUntilWrittenAndNeverWritesPlainText() {
        prefs.edit().putString("access_token", "old-access").apply()
        val s = settings(BrokenStore())
        assertEquals("old-access", s.accessToken)
        assertTrue(prefs.contains("access_token"))

        s.accessToken = "new-access"
        assertEquals("new-access", s.accessToken)
        assertFalse(prefs.contains("access_token"))
        assertFalse(prefs.all.values.contains("new-access"))
    }

    @Test
    fun uploadAllowedNeedsTheSwitchAndAnAnsweredNotice() {
        val s = settings(MemorySecretStore())
        assertFalse(s.uploadAllowed) // notice not answered yet
        s.uploadNoticeAnswered = true
        assertTrue(s.uploadAllowed)
        s.useServer = false
        assertFalse(s.uploadAllowed)
        assertFalse(ServerSettings(prefs, "", "") { MemorySecretStore() }.apply { useServer = true }.uploadAllowed)
    }
}
