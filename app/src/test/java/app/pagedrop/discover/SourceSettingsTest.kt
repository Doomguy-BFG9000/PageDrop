package app.pagedrop.discover

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceSettingsTest {
    private fun opdsHttp() = FakeHttp().apply { on("lib.example.org/opds", fixture("opds_root.xml")) }

    @Test
    fun builtInsDefaultOnAndTogglePersists() {
        val prefs = app.pagedrop.data.NullSharedPreferences()
        val http = FakeHttp()
        val s1 = SourceSettings(prefs, MemorySecrets(), http)
        assertEquals(setOf(SourceKind.GUTENBERG, SourceKind.STANDARD_EBOOKS, SourceKind.INTERNET_ARCHIVE), s1.enabledBuiltIns.value)
        s1.setEnabled(SourceKind.INTERNET_ARCHIVE, false)
        assertFalse(SourceKind.INTERNET_ARCHIVE in s1.enabledBuiltIns.value)
        val s2 = SourceSettings(prefs, MemorySecrets(), http) // "restart"
        assertFalse(SourceKind.INTERNET_ARCHIVE in s2.enabledBuiltIns.value)
        s2.setEnabled(SourceKind.INTERNET_ARCHIVE, true)
        assertTrue(SourceKind.INTERNET_ARCHIVE in s2.enabledBuiltIns.value)
    }

    @Test
    fun noCatalogsArePrefilled() {
        assertTrue(newSettings().catalogs.value.isEmpty())
    }

    @Test
    fun addCatalogValidatesStoresAndSurvivesRestart() = runBlocking {
        val prefs = app.pagedrop.data.NullSharedPreferences()
        val secrets = MemorySecrets()
        val s = SourceSettings(prefs, secrets, opdsHttp())
        val cat = s.addCatalog("lib.example.org/opds/", "sam", "pw").getOrThrow()
        assertEquals("Example Library", cat.title)
        assertEquals("https://lib.example.org/opds/", cat.url)
        assertEquals("sam", cat.username)
        assertEquals(listOf(cat), s.catalogs.value)
        // Password goes to the secret store, never into the plain preferences.
        assertEquals("pw", secrets.map.values.single())
        assertFalse(prefs.all.values.any { it.toString().contains("pw\"") || it.toString() == "pw" })
        val restarted = SourceSettings(prefs, secrets, opdsHttp())
        assertEquals(listOf(cat), restarted.catalogs.value)
        assertEquals(BasicAuth("sam", "pw"), restarted.credentialsFor(cat.id))
        restarted.setCatalogEnabled(cat.id, false)
        assertFalse(restarted.catalogs.value.single().enabled)
        restarted.removeCatalog(cat.id)
        assertTrue(restarted.catalogs.value.isEmpty())
        assertTrue(secrets.map.isEmpty())
        assertNull(restarted.credentialsFor(cat.id))
    }

    @Test
    fun duplicatesAndCredentialsOverHttpAreRejected() = runBlocking {
        val s = newSettings(opdsHttp())
        s.addCatalog("https://lib.example.org/opds/", null, null).getOrThrow()
        assertTrue(s.addCatalog("https://lib.example.org/opds/", null, null).exceptionOrNull()!!.message!!.contains("already"))
        val plain = s.addCatalog("http://other.example.org/opds", "u", "p").exceptionOrNull()!!
        assertTrue(plain.message!!.contains("https"))
    }

    @Test
    fun failuresAreExplainedInPlainWords() = runBlocking {
        val http = FakeHttp().apply {
            on("private.example.org", "", code = 401)
            on("json.example.org", """{"metadata":{}}""")
            on("html.example.org", "<html>hello</html>")
            fail("down.example.org", java.net.UnknownHostException("down.example.org"))
        }
        val s = newSettings(http)
        assertTrue(s.addCatalog("https://private.example.org/opds", null, null).exceptionOrNull()!!.message!!.contains("username"))
        assertTrue(s.addCatalog("https://private.example.org/opds", "u", "p").exceptionOrNull()!!.message!!.contains("rejected"))
        assertTrue(s.addCatalog("https://json.example.org/opds", null, null).exceptionOrNull()!!.message!!.contains("OPDS 2"))
        assertTrue(s.addCatalog("https://html.example.org/opds", null, null).exceptionOrNull()!!.message!!.contains("OPDS"))
        assertTrue(s.addCatalog("https://down.example.org/opds", null, null).exceptionOrNull()!!.message!!.contains("reach"))
        assertTrue(s.addCatalog("not a url", null, null).isFailure)
        assertTrue(s.catalogs.value.isEmpty())
    }

    @Test
    fun urlNormalizationAndStableIds() {
        assertEquals("https://example.org/opds", SourceSettings.normalizeUrl(" example.org/opds "))
        assertEquals(SourceSettings.catalogId("https://Example.org/opds/"), SourceSettings.catalogId("https://example.org/opds"))
    }
}
