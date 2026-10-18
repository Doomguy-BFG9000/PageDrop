package app.pagedrop.transfer.ssh

import android.content.SharedPreferences
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.UserInfo
import java.security.MessageDigest
import java.util.Base64

/**
 * Trust-on-first-use pins: the SSH host key each Kindle presented on its first successful login,
 * keyed by address and port. Public keys only, but excluded from backup so a restored phone
 * starts fresh instead of trusting keys it never saw.
 */
internal class HostKeyStore(private val prefs: SharedPreferences) {
    fun pinned(hostId: String): ByteArray? =
        prefs.getString(hostId, null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    fun pin(hostId: String, key: ByteArray) {
        prefs.edit().putString(hostId, Base64.getEncoder().encodeToString(key)).apply()
    }

    companion object {
        /** Must match the exclusions in res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val PREFS = "kindle_host_keys"

        fun hostId(host: String, port: Int): String = "${host.trim().lowercase()}:$port"
    }
}

/** OpenSSH-style fingerprint, e.g. `SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU`. */
internal fun fingerprint(key: ByteArray): String =
    "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(key))

/**
 * One connection's view of the pin for the Kindle it is connecting to. JSch asks [check] during key
 * exchange. Unknown hosts are let through and remembered in [firstSeen] so the caller can pin the
 * key once login succeeds; a key that differs from the pin is reported as CHANGED, which makes
 * JSch abort (StrictHostKeyChecking=yes), and is kept in [mismatch] for the user-facing prompt.
 */
internal class PinningHostKeyRepository(private val pinned: ByteArray?) : HostKeyRepository {
    @Volatile var firstSeen: ByteArray? = null
        private set
    @Volatile var mismatch: ByteArray? = null
        private set

    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        return when {
            pinned == null -> { firstSeen = key.copyOf(); HostKeyRepository.OK }
            MessageDigest.isEqual(pinned, key) -> HostKeyRepository.OK
            else -> { mismatch = key.copyOf(); HostKeyRepository.CHANGED }
        }
    }

    // Lets JSch prefer the pinned key's algorithm, so a Kindle with several host keys keeps
    // presenting the one we pinned.
    override fun getHostKey(host: String?, type: String?): Array<HostKey> {
        val key = pinned ?: return emptyArray()
        val hostKey = runCatching { HostKey(host ?: "", key) }.getOrNull() ?: return emptyArray()
        return if (type == null || hostKey.type == type) arrayOf(hostKey) else emptyArray()
    }

    override fun getHostKey(): Array<HostKey> = emptyArray()

    // Pins are written by KindleSsh after login, never by JSch itself.
    override fun add(hostkey: HostKey?, ui: UserInfo?) {}
    override fun remove(host: String?, type: String?) {}
    override fun remove(host: String?, type: String?, key: ByteArray?) {}
    override fun getKnownHostsRepositoryID(): String = "pagedrop-pinned"
}
