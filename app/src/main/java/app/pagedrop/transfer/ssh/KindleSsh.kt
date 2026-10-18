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
        session.timeout = SOCKET_READ_TIMEOUT_MS // a silent Kindle must not block reads forever
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
     * Adds this phone's key (generated if needed) to KOReader's authorized_keys over a session that
     * logs in with the current settings.
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

    /**
     * Shell run on the Kindle (BusyBox sh). Appends [line] to KOReader's authorized_keys unless it is
     * already there, first adding a newline if the file doesn't end with one, so a hand-edited file
     * can't have the new key glued onto its last line. chmod failures are ignored: on FAT-backed
     * /mnt/us the call may not be supported, and the key is already saved by then.
     * USBNetwork's authorized_keys is deliberately not touched in this release.
     */
    internal fun installScript(line: String, root: String = KINDLE_ROOT): String {
        val dir = "$root/$KOREADER_SSH_DIR"
        val file = "$dir/authorized_keys"
        // `line` is checked against SAFE_KEY_LINE, so single quotes can't be broken out of.
        return listOf(
            "mkdir -p '$dir'",
            "chmod 700 '$dir' 2>/dev/null || true",
            "f='$file'",
            "{ grep -qxF '$line' \"\$f\" 2>/dev/null || { { [ -s \"\$f\" ] && [ -n \"\$(tail -c1 \"\$f\")\" ] && echo >> \"\$f\"; } ; echo '$line' >> \"\$f\"; }; }",
            "chmod 600 \"\$f\" 2>/dev/null || true",
            "sync",
        ).joinToString(" && ")
    }

    /** Socket read timeout for every session; a stalled Kindle fails with an error instead of hanging. */
    const val SOCKET_READ_TIMEOUT_MS = 20_000

    private const val KINDLE_ROOT = "/mnt/us"
    private const val KOREADER_SSH_DIR = "koreader/settings/SSH"
    private val SAFE_KEY_LINE = Regex("^[a-z0-9-]+ [A-Za-z0-9+/=]+ [A-Za-z0-9@._-]+$")
}

/**
 * Runs [command] on a connected session and waits up to [timeoutMs]; returns exit status and combined
 * output (status -1 on timeout). Reads happen on a helper thread, so a Kindle that goes silent can't
 * block the caller past the deadline.
 */
internal fun Session.exec(command: String, timeoutMs: Int): Pair<Int, String> {
    val channel = openChannel("exec") as ChannelExec
    try {
        channel.setCommand("( $command ) 2>&1")
        val out = channel.inputStream
        channel.connect(timeoutMs)
        val reader = readInBackground(out)
        val deadline = System.currentTimeMillis() + timeoutMs
        reader.join(timeoutMs.toLong())
        while (!channel.isClosed && System.currentTimeMillis() < deadline) Thread.sleep(50)
        val finished = !reader.isAlive && channel.isClosed
        return (if (finished) channel.exitStatus else -1) to reader.text().trim()
    } finally {
        channel.disconnect()
    }
}

/** Collects a stream on a daemon thread; closing the channel unblocks and ends it. */
internal class BackgroundReader(private val stream: java.io.InputStream) : Thread("kindle-ssh-reader") {
    private val buffer = StringBuffer()

    init { isDaemon = true }

    override fun run() {
        try {
            stream.bufferedReader().use { r ->
                val chunk = CharArray(4096)
                while (true) {
                    val n = r.read(chunk)
                    if (n < 0) break
                    buffer.append(chunk, 0, n)
                }
            }
        } catch (_: Exception) {
            // Channel closed or socket timed out: whatever arrived so far is the output.
        }
    }

    fun text(): String = buffer.toString()
}

internal fun readInBackground(stream: java.io.InputStream): BackgroundReader =
    BackgroundReader(stream).also { it.start() }
