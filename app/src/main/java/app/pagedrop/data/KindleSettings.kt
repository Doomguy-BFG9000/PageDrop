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

    var host: String
        get() = prefs.getString("host", "") ?: ""
        set(value) = prefs.edit().putString("host", value).apply()

    var port: Int
        get() = prefs.getInt("port", 22)
        set(value) = prefs.edit().putInt("port", value).apply()

    var username: String
        get() = prefs.getString("username", "root") ?: "root"
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "") ?: ""
        set(value) = prefs.edit().putString("password", value).apply()

    var targetDirectory: String
        get() = prefs.getString("target_directory", "/mnt/us/documents") ?: "/mnt/us/documents"
        set(value) = prefs.edit().putString("target_directory", value).apply()

    var targetDirectories: List<String>
        get() = listOf(targetDirectory) + (prefs.getStringSet("target_directories", null)
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.filter { it != targetDirectory }
            .orEmpty())
        set(value) {
            val paths = value.map { it.trim() }.filter { it.isNotBlank() }.distinct()
                .ifEmpty { listOf("/mnt/us/documents") }
            prefs.edit()
                .putStringSet("target_directories", paths.toSet())
                .putString("target_directory", paths.first())
                .apply()
        }

    var triggerRescan: Boolean
        get() = prefs.getBoolean("trigger_rescan", true)
        set(value) = prefs.edit().putBoolean("trigger_rescan", value).apply()
}

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
