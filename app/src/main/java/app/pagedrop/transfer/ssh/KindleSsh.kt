package app.pagedrop.transfer.ssh

import android.content.Context
import app.pagedrop.data.NullSharedPreferences
import app.pagedrop.security.KindleSecrets
import app.pagedrop.security.MemorySecretStore
import app.pagedrop.security.SecretStore
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Base64

/** A Kindle presented a different host key than the one pinned on first connect. */
data class HostKeyChange(
    val host: String,
    val port: Int,
    val pinnedFingerprint: String,
    val presentedFingerprint: String,
    internal val presentedKey: String,
)

class HostKeyChangedException(val change: HostKeyChange, cause: Throwable? = null) : JSchException(
    "Your Kindle at ${change.host} identified itself differently than before. If you reinstalled KOReader " +
        "or reset the Kindle, confirm it is yours to trust its new key. Otherwise another device on this " +
        "network may be pretending to be your Kindle.",
    cause,
)

/**
 * The only place PageDrop opens SSH sessions to a Kindle. Host keys are checked trust-on-first-use
 * ([HostKeyStore]); this phone's key ([ClientKey]) is offered before the password once it exists.
 */
object KindleSsh {
    @Volatile private var hostKeys = HostKeyStore(NullSharedPreferences())
    @Volatile private var clientKey = ClientKey(MemorySecretStore())

    private val _hostKeyChange = MutableStateFlow<HostKeyChange?>(null)

    /** The most recent rejected host key, until the user trusts or dismisses it. Shown app-wide. */
    val hostKeyChange: StateFlow<HostKeyChange?> = _hostKeyChange.asStateFlow()

    /** Called from Application.onCreate. Until then pins and keys live only in memory. */
    fun init(context: Context) {
        val app = context.applicationContext
        install(
            HostKeyStore(app.getSharedPreferences(HostKeyStore.PREFS, Context.MODE_PRIVATE)),
            KindleSecrets.store(app),
        )
    }

    internal fun install(hostKeys: HostKeyStore, secrets: SecretStore) {
        this.hostKeys = hostKeys
        this.clientKey = ClientKey(secrets)
        _hostKeyChange.value = null
    }

    /**
     * Opens and connects a session; the caller disconnects it. [keyOnly] skips password login,
     * to check that key login works on its own.
     *
     * @throws HostKeyChangedException when the Kindle's key differs from the pinned one
     */
    fun connect(host: String, port: Int, user: String, pass: String, timeoutMs: Int, keyOnly: Boolean = false): Session {
        val jsch = JSch()
        clientKey.addTo(jsch)
        val hostId = HostKeyStore.hostId(host, port)
        val repository = PinningHostKeyRepository(hostKeys.pinned(hostId))
        val session = jsch.getSession(user, host, port)
        session.setHostKeyRepository(repository)
        session.setConfig("StrictHostKeyChecking", "yes")
        if (keyOnly) session.setConfig("PreferredAuthentications", "publickey") else session.setPassword(pass)
        try {
            session.connect(timeoutMs)
        } catch (e: JSchException) {
            val presented = repository.mismatch ?: throw e
            val pinned = hostKeys.pinned(hostId) ?: throw e
            val change = HostKeyChange(host.trim(), port, fingerprint(pinned), fingerprint(presented), Base64.getEncoder().encodeToString(presented))
            _hostKeyChange.value = change
            throw HostKeyChangedException(change, e)
        }
        // Pin only after login succeeded, so a failed attempt never decides which key is trusted.
        repository.firstSeen?.let { hostKeys.pin(hostId, it) }
        return session
    }

    /** "This is my Kindle": replace the pin with the key it presented, e.g. after reinstalling KOReader. */
    fun trustNewKey(change: HostKeyChange) {
        hostKeys.pin(HostKeyStore.hostId(change.host, change.port), Base64.getDecoder().decode(change.presentedKey))
        _hostKeyChange.compareAndSet(change, null)
    }

    fun dismissHostKeyChange() {
        _hostKeyChange.value = null
    }

    /** Fingerprint of this phone's key, or null if key login was never set up. */
    fun clientKeyFingerprint(): String? = clientKey.fingerprint()

    fun clientPublicKey(): String? = clientKey.publicKeyLine()

    /**
     * Adds this phone's key (generated if needed) to KOReader's authorized_keys, and USBNetwork's
     * if it is installed, over a session that logs in with the current settings.
     */
    fun installClientKey(host: String, port: Int, user: String, pass: String) {
        val line = clientKey.ensure()
        require(SAFE_KEY_LINE.matches(line)) { "Unexpected key format" }
        val session = connect(host, port, user, pass, 10_000)
        try {
            val (status, output) = session.exec(installScript(line), 10_000)
            if (status != 0) throw IllegalStateException("The Kindle couldn't save the key${output.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "."}")
        } finally {
            session.disconnect()
        }
    }

    /** True when the Kindle accepts this phone's key without any password. */
    fun verifyKeyLogin(host: String, port: Int, user: String): Boolean {
        if (clientKey.publicKeyLine() == null) return false
        return try {
            connect(host, port, user, "", 10_000, keyOnly = true).disconnect()
            true
        } catch (e: JSchException) {
            // Only a refused login means "no key login"; unreachable Kindles and changed host keys propagate.
            if (e is HostKeyChangedException || e.message?.contains("Auth", ignoreCase = true) != true) throw e
            false
        }
    }

    internal fun installScript(line: String, root: String = KINDLE_ROOT): String {
        val koreaderDir = "$root/$KOREADER_SSH_DIR"
        val dirs = listOf(koreaderDir, "$root/$USBNET_DIR")
        // `line` is checked against SAFE_KEY_LINE, so single quotes can't be broken out of.
        return dirs.joinToString(" && ") { dir ->
            val file = "$dir/authorized_keys"
            if (dir == koreaderDir) {
                "mkdir -p '$dir' && { grep -qxF '$line' '$file' 2>/dev/null || echo '$line' >> '$file'; } && chmod 600 '$file'"
            } else {
                "{ [ ! -d '$dir' ] || grep -qxF '$line' '$file' 2>/dev/null || echo '$line' >> '$file'; }"
            }
        } + " && sync"
    }

    private const val KINDLE_ROOT = "/mnt/us"
    private const val KOREADER_SSH_DIR = "koreader/settings/SSH"
    private const val USBNET_DIR = "usbnet/etc"
    private val SAFE_KEY_LINE = Regex("^[a-z0-9-]+ [A-Za-z0-9+/=]+ [A-Za-z0-9@._-]+$")
}

/** Runs [command] on a connected session and waits up to [timeoutMs]; returns exit status and combined output. */
internal fun Session.exec(command: String, timeoutMs: Int): Pair<Int, String> {
    val channel = openChannel("exec") as ChannelExec
    try {
        channel.setCommand("( $command ) 2>&1")
        val out = channel.inputStream
        channel.connect(timeoutMs)
        val text = out.bufferedReader().readText()
        var waited = 0
        while (!channel.isClosed && waited < timeoutMs) {
            Thread.sleep(50)
            waited += 50
        }
        return (if (channel.isClosed) channel.exitStatus else -1) to text.trim()
    } finally {
        channel.disconnect()
    }
}
