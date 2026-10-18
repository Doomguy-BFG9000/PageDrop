package app.pagedrop.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores secrets. Implementations must never persist plain text; [put] may throw (then nothing is saved). */
internal interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Process-lifetime only; used before [app.pagedrop.transfer.ssh.KindleSsh.init] and in tests. */
internal class MemorySecretStore : SecretStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * AES-GCM with a non-exportable Android Keystore key, ciphertext in private SharedPreferences. No extra dependency.
 * The key never leaves this device, so these files are excluded from backup (res/xml/backup_rules.xml).
 */
internal class KeystoreSecretStore(
    private val prefs: SharedPreferences,
    private val alias: String,
) : SecretStore {
    override fun get(key: String): String? = try {
        prefs.getString(key, null)?.let { stored ->
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv)) }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read stored secret: ${e.javaClass.simpleName}")
        null
    }

    override fun put(key: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(key, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                alias,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val TAG = "SecretStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/**
 * Where Kindle login secrets live: the SSH password and this phone's SSH key. Shared by
 * [app.pagedrop.data.KindleSettings] and [app.pagedrop.transfer.ssh.KindleSsh].
 */
internal object KindleSecrets {
    /** Must match the exclusions in res/xml/backup_rules.xml and data_extraction_rules.xml. */
    const val PREFS = "kindle_secrets"
    private const val KEY_ALIAS = "pagedrop_kindle_credentials"

    fun store(context: Context): SecretStore =
        KeystoreSecretStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), KEY_ALIAS)
}
