package app.pagedrop.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KindleSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Test-only constructor — bypasses SharedPreferences
    internal constructor() : this(object : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NullSharedPreferences()
    })

    private val prefs: SharedPreferences by lazy {
        try {
            context.getSharedPreferences("kindle_settings", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            NullSharedPreferences()
        }
    }

    /** True once the user has entered a Kindle address. */
    val isConfigured: Boolean
        get() = host.isNotBlank()

    /** True when the user chose "Set up later" on the first-run screen. */
    var setupSkipped: Boolean
        get() = prefs.getBoolean("setup_skipped", false)
        set(value) = prefs.edit().putBoolean("setup_skipped", value).apply()

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value).apply()

    var port: Int
        get() = prefs.getInt("port", DEFAULT_PORT)
        set(value) = prefs.edit().putInt("port", value).apply()

    var username: String
        get() = prefs.getString("username", "root") ?: "root"
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    /** The first (primary) folder. Setting it replaces the first entry of [targetDirectories]. */
    var targetDirectory: String
        get() = targetDirectories.first()
        set(value) {
            targetDirectories = listOf(value) + targetDirectories.drop(1)
        }

    /**
     * Ordered folder list, stored as one newline-joined string. Older installs kept the primary
     * in "target_directory" and the rest in an unordered StringSet; those are read as a fallback
     * and replaced by the new key on the next write.
     */
    var targetDirectories: List<String>
        get() {
            prefs.getString(KEY_DIRECTORIES, null)?.let { stored ->
                return cleanPaths(stored.lines()).ifEmpty { listOf(DEFAULT_DIRECTORY) }
            }
            val legacyPrimary = prefs.getString("target_directory", null)
            val legacyExtras = prefs.getStringSet("target_directories", null).orEmpty().sorted()
            return cleanPaths(listOfNotNull(legacyPrimary) + legacyExtras).ifEmpty { listOf(DEFAULT_DIRECTORY) }
        }
        set(value) {
            val paths = cleanPaths(value).ifEmpty { listOf(DEFAULT_DIRECTORY) }
            prefs.edit()
                .putString(KEY_DIRECTORIES, paths.joinToString("\n"))
                .remove("target_directories")
                .remove("target_directory")
                .apply()
        }

    private fun cleanPaths(raw: List<String>) = raw.map { it.trim() }.filter { it.isNotBlank() }.distinct()

    var triggerRescan: Boolean
        get() = prefs.getBoolean("trigger_rescan", true)
        set(value) = prefs.edit().putBoolean("trigger_rescan", value).apply()
}

private const val KEY_DIRECTORIES = "target_directories_v2"
private const val DEFAULT_DIRECTORY = "/mnt/us/documents"

const val DEFAULT_PORT = 2222 // KOReader's built-in SSH server

internal class NullSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = key in map
    override fun edit(): SharedPreferences.Editor = NullEditor(map)
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

internal class NullEditor(private val map: MutableMap<String, Any?>) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) map[key] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
        if (key != null) map[key] = values
        return this
    }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) map[key] = value; return this }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) map[key] = value; return this }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) map[key] = value; return this }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) map[key] = value; return this }
    override fun remove(key: String?): SharedPreferences.Editor { if (key != null) map.remove(key); return this }
    override fun clear(): SharedPreferences.Editor { map.clear(); return this }
    override fun commit(): Boolean = true
    override fun apply() { }
}
