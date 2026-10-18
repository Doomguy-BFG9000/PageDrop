package app.pagedrop.transfer.ssh

import app.pagedrop.security.SecretStore
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * This phone's SSH key for logging in to the Kindle without a password. Generated on demand
 * (ECDSA P-256: built into Android's crypto provider and supported by KOReader's dropbear), kept
 * only in the encrypted [SecretStore], and offered on every connection once it exists.
 */
internal class ClientKey(private val secrets: SecretStore) {

    /** The `authorized_keys` line, or null before [ensure] has run. */
    fun publicKeyLine(): String? = secrets.get(KEY_PUBLIC)?.trim()?.takeIf { it.isNotEmpty() }

    fun fingerprint(): String? = publicKeyLine()?.split(' ')?.getOrNull(1)
        ?.let { runCatching { fingerprint(Base64.getDecoder().decode(it)) }.getOrNull() }

    /** Returns the public key line, generating and storing a new key pair first if there is none. */
    @Synchronized
    fun ensure(): String {
        publicKeyLine()?.let { if (secrets.get(KEY_PRIVATE) != null) return it }
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ECDSA, 256)
        try {
            val private = ByteArrayOutputStream().also { pair.writePrivateKey(it) }.toString(Charsets.US_ASCII.name())
            val public = ByteArrayOutputStream().also { pair.writePublicKey(it, COMMENT) }.toString(Charsets.US_ASCII.name()).trim()
            secrets.put(KEY_PRIVATE, private)
            secrets.put(KEY_PUBLIC, public)
            return public
        } finally {
            pair.dispose()
        }
    }

    /** Offers the key on connections made with [jsch]; does nothing when there is no key yet. */
    fun addTo(jsch: JSch) {
        val private = secrets.get(KEY_PRIVATE) ?: return
        val public = publicKeyLine() ?: return
        jsch.addIdentity(COMMENT, private.toByteArray(Charsets.US_ASCII), public.toByteArray(Charsets.US_ASCII), null)
    }

    private companion object {
        const val KEY_PRIVATE = "ssh_client_private_key"
        const val KEY_PUBLIC = "ssh_client_public_key"
        const val COMMENT = "pagedrop"
    }
}
