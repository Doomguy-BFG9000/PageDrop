package app.pagedrop.transfer.ssh

import app.pagedrop.data.NullSharedPreferences
import app.pagedrop.security.MemorySecretStore
import app.pagedrop.ui.connection.ConnectionHints
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.keyprovider.KeyPairProvider
import org.apache.sshd.server.SshServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class KindleSshTest {
    private val servers = mutableListOf<SshServer>()
    private lateinit var hostKeys: HostKeyStore
    private lateinit var secrets: MemorySecretStore

    @Before
    fun setUp() {
        hostKeys = HostKeyStore(NullSharedPreferences())
        secrets = MemorySecretStore()
        KindleSsh.install(hostKeys, secrets)
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop(true) }
    }

    @Test
    fun repository_trustsFirstKeyAndRejectsADifferentOne() {
        val first = PinningHostKeyRepository(null)
        assertEquals(HostKeyRepository.OK, first.check("[kindle]:2222", byteArrayOf(1, 2, 3)))
        assertArrayEquals(byteArrayOf(1, 2, 3), first.firstSeen)

        val pinned = PinningHostKeyRepository(byteArrayOf(1, 2, 3))
        assertEquals(HostKeyRepository.OK, pinned.check("[kindle]:2222", byteArrayOf(1, 2, 3)))
        assertNull(pinned.firstSeen)
        assertEquals(HostKeyRepository.CHANGED, pinned.check("[kindle]:2222", byteArrayOf(9)))
        assertArrayEquals(byteArrayOf(9), pinned.mismatch)
    }

    @Test
    fun hostId_normalisesAddress() {
        assertEquals("kindle.local:2222", HostKeyStore.hostId(" Kindle.LOCAL ", 2222))
    }

    @Test
    fun firstLogin_pinsHostKey_laterLoginsMatch() {
        val server = startServer(ecKeyPair())
        assertNull(hostKeys.pinned(HostKeyStore.hostId("127.0.0.1", server.port)))

        KindleSsh.connect("127.0.0.1", server.port, "root", PASSWORD, 5_000).disconnect()
        assertNotNull(hostKeys.pinned(HostKeyStore.hostId("127.0.0.1", server.port)))

        KindleSsh.connect("127.0.0.1", server.port, "root", PASSWORD, 5_000).disconnect()
        assertNull(KindleSsh.hostKeyChange.value)
    }

    @Test
    fun failedLogin_doesNotPin() {
        val server = startServer(ecKeyPair())
        runCatching { KindleSsh.connect("127.0.0.1", server.port, "root", "wrong", 5_000) }
            .onSuccess { fail("wrong password logged in") }
        assertNull(hostKeys.pinned(HostKeyStore.hostId("127.0.0.1", server.port)))
    }

    @Test
    fun changedHostKey_isRejectedUntilTheUserTrustsIt() {
        val original = startServer(ecKeyPair())
        val port = original.port
        KindleSsh.connect("127.0.0.1", port, "root", PASSWORD, 5_000).disconnect()
        original.stop(true)

        // Same address, new identity: KOReader reinstalled, or someone impersonating the Kindle.
        startServer(ecKeyPair(), port)
        val error = runCatching { KindleSsh.connect("127.0.0.1", port, "root", PASSWORD, 5_000) }.exceptionOrNull()
        assertTrue("expected HostKeyChangedException, got $error", error is HostKeyChangedException)
        val change = KindleSsh.hostKeyChange.value
        assertNotNull(change)
        assertEquals((error as HostKeyChangedException).change, change)
        assertTrue(change!!.pinnedFingerprint != change.presentedFingerprint)
        assertEquals("Your Kindle's identity changed", ConnectionHints.explain(error, port).title)

        KindleSsh.trustNewKey(change)
        assertNull(KindleSsh.hostKeyChange.value)
        KindleSsh.connect("127.0.0.1", port, "root", PASSWORD, 5_000).disconnect()
    }

    @Test
    fun keyLogin_worksOnceTheKindleHasThePublicKey() {
        val accepted = mutableSetOf<String>()
        val server = startServer(ecKeyPair()) { key -> PublicKeyEntry.toString(key) in accepted }

        assertFalse("no key yet", KindleSsh.verifyKeyLogin("127.0.0.1", server.port, "root"))
        val line = ClientKey(secrets).ensure()
        assertFalse("key not on the Kindle yet", KindleSsh.verifyKeyLogin("127.0.0.1", server.port, "root"))

        accepted += line.substringBeforeLast(' ')
        assertTrue(KindleSsh.verifyKeyLogin("127.0.0.1", server.port, "root"))
        assertNotNull(KindleSsh.clientKeyFingerprint())
    }

    @Test
    fun clientKey_isGeneratedOnceAndLoadsIntoJSch() {
        val key = ClientKey(secrets)
        assertNull(key.publicKeyLine())
        val line = key.ensure()
        assertTrue(line, line.startsWith("ecdsa-sha2-nistp256 ") && line.endsWith(" pagedrop"))
        assertEquals(line, key.ensure())
        assertTrue(key.fingerprint()!!.startsWith("SHA256:"))
        JSch().also { key.addTo(it) }.identityNames.let { assertEquals(listOf("pagedrop"), it.toList()) }
    }

    @Test
    fun installScript_appendsOnceAndSkipsMissingUsbNetwork() {
        val root = Files.createTempDirectory("kindle").toFile()
        try {
            val line = ClientKey(secrets).ensure()
            repeat(2) { runShell(KindleSsh.installScript(line, root.path)) }
            assertEquals(listOf(line), File(root, "koreader/settings/SSH/authorized_keys").readLines())
            assertFalse(File(root, "usbnet").exists())

            File(root, "usbnet/etc").mkdirs()
            runShell(KindleSsh.installScript(line, root.path))
            assertEquals(listOf(line), File(root, "usbnet/etc/authorized_keys").readLines())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun runShell(script: String) {
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(output, 0, process.waitFor())
    }

    private fun startServer(
        hostKey: KeyPair,
        port: Int = 0,
        acceptKey: (java.security.PublicKey) -> Boolean = { false },
    ): SshServer = SshServer.setUpDefaultServer().apply {
        this.port = port
        host = "127.0.0.1"
        keyPairProvider = KeyPairProvider.wrap(hostKey)
        setPasswordAuthenticator { _, password, _ -> password == PASSWORD }
        setPublickeyAuthenticator { _, key, _ -> acceptKey(key) }
        start()
        servers += this
    }

    private fun ecKeyPair(): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private companion object {
        const val PASSWORD = "kindle"
    }
}
