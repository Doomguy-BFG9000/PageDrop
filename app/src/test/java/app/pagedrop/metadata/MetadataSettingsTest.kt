package app.pagedrop.metadata

import app.pagedrop.data.NullSharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSettingsTest {
    @Test fun defaultFollowsWhetherTheBuildHasAServer() {
        assertTrue(MetadataSettings.defaultUseGoogleBooks("https://example.supabase.co"))
        assertFalse(MetadataSettings.defaultUseGoogleBooks(""))
        assertFalse(MetadataSettings.defaultUseGoogleBooks("  "))
    }

    @Test fun googleBooksOnByDefaultWithServer() {
        assertTrue(MetadataSettings(NullSharedPreferences(), defaultUseGoogleBooks = true).useGoogleBooks.value)
    }

    @Test fun googleBooksOffByDefaultWithoutServer() {
        assertFalse(MetadataSettings(NullSharedPreferences(), defaultUseGoogleBooks = false).useGoogleBooks.value)
    }

    @Test fun choicePersistsAndOverridesTheDefault() {
        val prefs = NullSharedPreferences()
        val first = MetadataSettings(prefs, defaultUseGoogleBooks = false)
        first.setUseGoogleBooks(true)
        assertTrue(first.useGoogleBooks.value)
        assertTrue(MetadataSettings(prefs, defaultUseGoogleBooks = false).useGoogleBooks.value)

        val other = NullSharedPreferences()
        MetadataSettings(other, defaultUseGoogleBooks = true).setUseGoogleBooks(false)
        assertFalse(MetadataSettings(other, defaultUseGoogleBooks = true).useGoogleBooks.value)
    }
}
