package app.pagedrop.data

import app.pagedrop.security.MemorySecretStore
import app.pagedrop.security.SecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleSettingsPasswordTest {
    private val prefs = NullSharedPreferences()
    private val context = object : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int) = prefs
    }

    /** A Keystore that refuses to encrypt (put throws) but otherwise behaves. */
    private class BrokenStore : SecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = throw IllegalStateException("no keystore")
        override fun remove(key: String) {}
    }

    @Test
    fun password_isKeptInTheSecretStoreNotPreferences() {
        val secrets = MemorySecretStore()
        val settings = KindleSettings(context) { secrets }

        settings.password = "hunter2"

        assertEquals("hunter2", settings.password)
        assertEquals("hunter2", secrets.get("ssh_password"))
        assertFalse(prefs.all.values.contains("hunter2"))
        settings.password = ""
        assertEquals("", settings.password)
        assertNull(secrets.get("ssh_password"))
    }

    @Test
    fun legacyPlainTextPassword_isMovedAndErased() {
        prefs.edit().putString("password", "old-secret").apply()
        val secrets = MemorySecretStore()
        val settings = KindleSettings(context) { secrets }

        assertEquals("old-secret", settings.password)
        assertFalse(prefs.contains("password"))
        assertEquals("old-secret", secrets.get("ssh_password"))
    }

    @Test
    fun keystorePutFails_legacyPasswordIsStillUsedAndKept() {
        prefs.edit().putString("password", "old-secret").apply()
        val settings = KindleSettings(context) { BrokenStore() }

        assertEquals("old-secret", settings.password)
        assertTrue(prefs.contains("password"))
        assertFalse(settings.passwordUnreadable)
    }

    @Test
    fun migrationIsNotTrusted_untilTheEncryptedCopyReadsBack() {
        prefs.edit().putString("password", "old-secret").apply()
        val lossy = object : SecretStore {
            override fun get(key: String): String? = null // write "succeeds" but can't be read back
            override fun put(key: String, value: String) {}
            override fun remove(key: String) {}
        }
        val settings = KindleSettings(context) { lossy }

        assertEquals("old-secret", settings.password)
        assertTrue(prefs.contains("password"))
    }

    @Test
    fun newPasswordIsNeverWrittenInPlainText_whenEncryptionFails() {
        val settings = KindleSettings(context) { BrokenStore() }

        settings.password = "new-secret"

        assertEquals("new-secret", settings.password) // in memory for this run
        assertFalse(prefs.all.values.contains("new-secret"))
    }

    @Test
    fun changingThePassword_removesLegacyCopy_soItCannotComeBack() {
        prefs.edit().putString("password", "old-secret").apply()
        val secrets = MemorySecretStore()
        val settings = KindleSettings(context) { secrets }

        settings.password = "new-secret" // before anything read (and migrated) the legacy value

        assertFalse(prefs.contains("password"))
        assertEquals("new-secret", settings.password)
        assertEquals("new-secret", secrets.get("ssh_password"))
    }

    @Test
    fun changingThePassword_whileKeystoreIsBroken_dropsLegacyToo() {
        prefs.edit().putString("password", "old-secret").apply()
        val settings = KindleSettings(context) { BrokenStore() }

        settings.password = "new-secret"

        assertFalse(prefs.contains("password"))
        assertEquals("new-secret", settings.password)
    }

    @Test
    fun clearingThePassword_doesNotResurrectLegacy() {
        prefs.edit().putString("password", "old-secret").apply()
        val settings = KindleSettings(context) { BrokenStore() }

        settings.password = ""

        assertEquals("", settings.password)
        assertFalse(prefs.contains("password"))
    }

    @Test
    fun usersWithoutAPassword_areUnaffected() {
        prefs.edit().putString("password", "").apply() // legacy empty value
        val secrets = MemorySecretStore()
        val settings = KindleSettings(context) { secrets }

        assertEquals("", settings.password)
        assertFalse(prefs.contains("password"))
        assertNull(secrets.get("ssh_password"))
        assertFalse(settings.passwordUnreadable)

        assertEquals("", KindleSettings(context) { BrokenStore() }.password)
    }

    @Test
    fun savedButUndecryptablePassword_isFlagged() {
        val unreadable = object : SecretStore {
            override fun get(key: String): String? = null
            override fun hasEntry(key: String) = true
            override fun put(key: String, value: String) {}
            override fun remove(key: String) {}
        }
        val settings = KindleSettings(context) { unreadable }

        assertEquals("", settings.password)
        assertTrue(settings.passwordUnreadable)
    }
}
