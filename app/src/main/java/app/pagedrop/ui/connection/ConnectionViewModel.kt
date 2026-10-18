package app.pagedrop.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.KindleSettings
import app.pagedrop.transfer.sftp.KindleSftpClient
import app.pagedrop.transfer.ssh.KindleSsh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    val kindleSettings: KindleSettings
) : ViewModel() {

    data class ConnectionState(
        val host: String = "",
        val port: String = "2222",
        val username: String = "root",
        val password: String = "",
        val isTesting: Boolean = false,
        val testResult: Result<Unit>? = null,
        val testedPort: Int = 2222,
        val keyFingerprint: String? = null,
        val keyBusy: Boolean = false,
        val keyResult: KeyLoginResult? = null,
        /** A password was saved but can't be decrypted any more; the user has to type it again. */
        val passwordUnreadable: Boolean = false,
    )

    sealed interface KeyLoginResult {
        /** The Kindle accepted this phone's key with no password. */
        data object Works : KeyLoginResult
        /** The key is in authorized_keys but the running SSH server hasn't picked it up yet. */
        data object NeedsRestart : KeyLoginResult
        data class Failed(val error: Throwable) : KeyLoginResult
    }

    private val _state = MutableStateFlow(
        ConnectionState(
            host = kindleSettings.host,
            port = kindleSettings.port.toString(),
            username = kindleSettings.username,
        )
    )
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    /** Blocking work (Keystore) runs here; tests replace it with their test dispatcher. */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    // Outlives the ViewModel so a password typed just before leaving the screen is still saved.
    private val persistScope = CoroutineScope(SupervisorJob())
    private var persistJob: Job? = null
    @Volatile private var passwordEdited = false

    init {
        // The saved password and key fingerprint come out of the Keystore, so never on the main thread.
        viewModelScope.launch {
            val (password, unreadable, fingerprint) = withContext(ioDispatcher) {
                Triple(kindleSettings.password, kindleSettings.passwordUnreadable, KindleSsh.clientKeyFingerprint())
            }
            _state.update {
                it.copy(
                    password = if (passwordEdited) it.password else password,
                    passwordUnreadable = unreadable && !passwordEdited,
                    keyFingerprint = fingerprint,
                )
            }
        }
    }

    fun updateHost(value: String) { _state.update { it.copy(host = value.trim()) }; kindleSettings.host = value.trim() }
    fun updatePort(value: String) {
        _state.update { it.copy(port = value) }
        kindleSettings.port = value.toIntOrNull() ?: app.pagedrop.data.DEFAULT_PORT
    }
    fun updateUsername(value: String) { _state.update { it.copy(username = value) }; kindleSettings.username = value }
    fun updatePassword(value: String) {
        passwordEdited = true
        _state.update { it.copy(password = value, passwordUnreadable = false) }
        persistJob?.cancel()
        persistJob = persistScope.launch(ioDispatcher) {
            delay(PASSWORD_SAVE_DELAY_MS)
            kindleSettings.password = value
        }
    }

    /** Saves the typed password now (test, save, focus loss) instead of waiting for the typing pause. */
    fun flushPassword() {
        if (!passwordEdited) return
        val value = _state.value.password
        persistJob?.cancel()
        persistJob = persistScope.launch(ioDispatcher) { kindleSettings.password = value }
    }

    private companion object {
        const val PASSWORD_SAVE_DELAY_MS = 500L
    }

    fun testConnection() {
        flushPassword()
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null) }
            val s = _state.value
            val port = s.port.toIntOrNull() ?: app.pagedrop.data.DEFAULT_PORT
            val result = KindleSftpClient.testConnection(
                host = s.host, port = port,
                user = s.username, pass = s.password
            )
            _state.update { it.copy(isTesting = false, testResult = result, testedPort = port) }
        }
    }

    /** Generates this phone's key if needed, adds it to the Kindle, then checks it logs in on its own. */
    fun setUpKeyLogin() = runKeyAction { s, port ->
        KindleSsh.installClientKey(s.host, port, s.username, s.password)
        if (KindleSsh.verifyKeyLogin(s.host, port, s.username)) KeyLoginResult.Works else KeyLoginResult.NeedsRestart
    }

    fun checkKeyLogin() = runKeyAction { s, port ->
        if (KindleSsh.verifyKeyLogin(s.host, port, s.username)) KeyLoginResult.Works else KeyLoginResult.NeedsRestart
    }

    private fun runKeyAction(action: (ConnectionState, Int) -> KeyLoginResult) {
        flushPassword()
        viewModelScope.launch {
            _state.update { it.copy(keyBusy = true, keyResult = null, testResult = null) }
            val s = _state.value
            val port = s.port.toIntOrNull() ?: app.pagedrop.data.DEFAULT_PORT
            val result = withContext(Dispatchers.IO) {
                try {
                    action(s, port)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    KeyLoginResult.Failed(e)
                }
            }
            _state.update {
                it.copy(keyBusy = false, keyResult = result, testedPort = port, keyFingerprint = KindleSsh.clientKeyFingerprint())
            }
        }
    }
}
