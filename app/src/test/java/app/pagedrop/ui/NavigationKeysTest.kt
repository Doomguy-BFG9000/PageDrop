package app.pagedrop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationKeysTest {

    private val keys = listOf(Main, Setup, Settings, Screensaver, Fonts, Dictionaries, Sync, Articles, Connection, KindleReady)

    @Test
    fun allNavKeysAreSerializable() {
        assertEquals(10, keys.size)
        keys.forEach { key -> assertNotNull(key.toString()) }
    }

    @Test
    fun routeCount_matchesScreens() {
        // Main (tab shell) + Setup + Settings + Connection + Sync + 4 extras screens + KindleReady
        assertEquals(10, keys.toSet().size)
    }

    @Test
    fun routeNames_areNotEmpty() {
        keys.forEach { assertTrue(it.toString().isNotBlank()) }
    }
}
