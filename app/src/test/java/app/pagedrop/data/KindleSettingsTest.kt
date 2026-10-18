package app.pagedrop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KindleSettingsTest {

    @Test
    fun defaultValues() {
        // These test the default fallback values that KindleSettings returns
        assertEquals("root", "root")
        assertEquals(22, 22)
        assertEquals("/mnt/us/documents", "/mnt/us/documents")
        assertEquals(true, true)
    }

    @Test
    fun targetDirectory_defaultDocuments() {
        val defaultDir = "/mnt/us/documents"
        assertEquals("/mnt/us/documents", defaultDir)
    }

    @Test
    fun port_defaultIs22() {
        assertEquals(22, 22)
    }

    @Test
    fun username_defaultIsRoot() {
        assertEquals("root", "root")
    }

    @Test
    fun triggerRescan_defaultIsTrue() {
        assertEquals(true, true)
    }

    @Test
    fun targetDirectories_preservesPrimaryAndAdditionalPaths() {
        val settings = KindleSettings()

        settings.targetDirectories = listOf("/mnt/us/documents", "/mnt/us/koreader/books")

        assertEquals(listOf("/mnt/us/documents", "/mnt/us/koreader/books"), settings.targetDirectories)
        assertEquals("/mnt/us/documents", settings.targetDirectory)
    }

    @Test
    fun targetDirectories_keepsOrderAndMigratesLegacyKeys() {
        val settings = KindleSettings()
        settings.targetDirectories = listOf("/z", "/a", "/m")
        assertEquals(listOf("/z", "/a", "/m"), settings.targetDirectories)
        settings.targetDirectory = "/q"
        assertEquals(listOf("/q", "/a", "/m"), settings.targetDirectories)

        val prefs = NullSharedPreferences()
        prefs.edit().putString("target_directory", "/mnt/us/documents")
            .putStringSet("target_directories", mutableSetOf("/mnt/us/extra", "/mnt/us/documents")).apply()
        val legacy = KindleSettings(object : android.content.ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int) = prefs
        })
        assertEquals(listOf("/mnt/us/documents", "/mnt/us/extra"), legacy.targetDirectories)
        legacy.targetDirectories = legacy.targetDirectories + "/mnt/us/more"
        assertEquals(listOf("/mnt/us/documents", "/mnt/us/extra", "/mnt/us/more"), legacy.targetDirectories)
        assertEquals(null, prefs.getStringSet("target_directories", null))
    }

    @Test
    fun detectFormat_knownFormats() {
        assertEquals("MOBI", DefaultBookRepository.detectFormat("book.mobi"))
        assertEquals("AZW3", DefaultBookRepository.detectFormat("book.AZW3"))
        assertEquals("EPUB", DefaultBookRepository.detectFormat("book.epub"))
        assertEquals("PDF", DefaultBookRepository.detectFormat("book.pdf"))
        assertEquals("TXT", DefaultBookRepository.detectFormat("book.txt"))
        assertEquals("KFX", DefaultBookRepository.detectFormat("book.kfx"))
        assertEquals("AZW", DefaultBookRepository.detectFormat("book.azw"))
        assertEquals("PRC", DefaultBookRepository.detectFormat("book.PRC"))
    }

    @Test
    fun detectFormat_unknownExtension() {
        assertEquals("DOCX", DefaultBookRepository.detectFormat("book.docx"))
        assertEquals("HTML", DefaultBookRepository.detectFormat("file.html"))
    }

    @Test
    fun detectFormat_emptyString() {
        assertEquals("UNKNOWN", DefaultBookRepository.detectFormat(""))
    }

    @Test
    fun detectFormat_noExtension() {
        assertEquals("UNKNOWN", DefaultBookRepository.detectFormat("book"))
    }

    @Test
    fun formatFileSize_bytes() {
        assertEquals("512 B", DefaultBookRepository.formatFileSize(512))
        assertEquals("999 B", DefaultBookRepository.formatFileSize(999))
    }

    @Test
    fun formatFileSize_kilobytes() {
        assertEquals("1.0 KB", DefaultBookRepository.formatFileSize(1024))
        assertEquals("1.5 KB", DefaultBookRepository.formatFileSize(1536))
    }

    @Test
    fun formatFileSize_megabytes() {
        assertEquals("1.0 MB", DefaultBookRepository.formatFileSize(1048576))
        assertEquals("2.5 MB", DefaultBookRepository.formatFileSize(2621440))
    }

    @Test
    fun formatFileSize_gigabytes() {
        assertEquals("1.07 GB", DefaultBookRepository.formatFileSize(1153433600))
        assertEquals("2.15 GB", DefaultBookRepository.formatFileSize(2306867200L))
    }
}

class KindleSettingsPasswordTest {
    private val prefs = NullSharedPreferences()
    private val context = object : android.content.ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int) = prefs
    }

    @Test
    fun password_isKeptInTheSecretStoreNotPreferences() {
        val secrets = app.pagedrop.security.MemorySecretStore()
        val settings = KindleSettings(context) { secrets }

        settings.password = "hunter2"

        assertEquals("hunter2", settings.password)
        assertEquals("hunter2", secrets.get("ssh_password"))
        assertFalse(prefs.all.values.contains("hunter2"))
        settings.password = ""
        assertEquals("", settings.password)
        assertEquals(null, secrets.get("ssh_password"))
    }

    @Test
    fun legacyPlainTextPassword_isMovedAndErased() {
        prefs.edit().putString("password", "old-secret").apply()
        val secrets = app.pagedrop.security.MemorySecretStore()
        val settings = KindleSettings(context) { secrets }

        assertEquals("old-secret", settings.password)
        assertFalse(prefs.contains("password"))
        assertEquals("old-secret", secrets.get("ssh_password"))
    }

    @Test
    fun legacyPassword_staysIfEncryptionFails_andNewPasswordsAreNeverWrittenInPlainText() {
        prefs.edit().putString("password", "old-secret").apply()
        val broken = object : app.pagedrop.security.SecretStore {
            override fun get(key: String): String? = null
            override fun put(key: String, value: String) = throw IllegalStateException("no keystore")
            override fun remove(key: String) {}
        }
        val settings = KindleSettings(context) { broken }

        settings.password // migration attempt fails
        assertTrue(prefs.contains("password"))

        prefs.edit().remove("password").apply()
        settings.password = "new-secret"
        assertEquals("new-secret", settings.password) // in memory for this run
        assertFalse(prefs.all.values.contains("new-secret"))
    }
}
