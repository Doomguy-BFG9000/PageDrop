package app.pagedrop.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** The tab shell (Library / Kindle / Extras) with the bottom navigation bar. */
@Serializable
data object Main : NavKey

/** "Connect your Kindle" — first run, or reopened from the Library banner / Kindle tab. */
@Serializable
data object Setup : NavKey

@Serializable
data object Settings : NavKey

@Serializable
data object Screensaver : NavKey

@Serializable
data object Fonts : NavKey

@Serializable
data object Dictionaries : NavKey

@Serializable
data object Sync : NavKey

@Serializable
data object Articles : NavKey

@Serializable
data object Connection : NavKey

/** "Get your Kindle ready" jailbreak/KOReader walkthrough. */
@Serializable
data object KindleReady : NavKey

/** Settings -> Book sources (built-in catalogs and OPDS). */
@Serializable
data object BookSources : NavKey

/** Browse one of the user's OPDS catalogs, starting at [feedUrl] (null = the catalog's root). */
@Serializable
data class CatalogRoute(val catalogId: String, val feedUrl: String? = null) : NavKey
