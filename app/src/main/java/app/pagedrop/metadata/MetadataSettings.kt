package app.pagedrop.metadata

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Which catalogs fill in book details. Open Library is always asked; Google Books is optional.
 *
 * Google Books defaults to on in builds that ship a conversion server and off in builds without one (the F-Droid
 * build), so those make no calls to non-free network services unless the user switches it on.
 */
@Singleton
class MetadataSettings internal constructor(
    private val prefs: SharedPreferences,
    private val defaultUseGoogleBooks: Boolean = defaultUseGoogleBooks(),
) {
    @Inject
    constructor(@ApplicationContext context: Context) :
        this(context.getSharedPreferences("pagedrop_metadata", Context.MODE_PRIVATE))

    private val _useGoogleBooks = MutableStateFlow(prefs.getBoolean(KEY_GOOGLE_BOOKS, defaultUseGoogleBooks))
    val useGoogleBooks: StateFlow<Boolean> = _useGoogleBooks.asStateFlow()

    fun setUseGoogleBooks(on: Boolean) {
        prefs.edit().putBoolean(KEY_GOOGLE_BOOKS, on).apply()
        _useGoogleBooks.value = on
    }

    internal companion object {
        private const val KEY_GOOGLE_BOOKS = "use_google_books"

        /** On only when this build has a conversion server URL. */
        fun defaultUseGoogleBooks(serverUrl: String = BuildConfig.PAGEDROP_SUPABASE_URL): Boolean = serverUrl.isNotBlank()
    }
}
