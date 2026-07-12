package app.pagedrop.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.KindleSettings
import app.pagedrop.server.PageDropApiClient
import app.pagedrop.server.ServerSettings
import app.pagedrop.transfer.sftp.KindleSftpClient
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    val kindleSettings: KindleSettings,
    private val serverSettings: ServerSettings,
    private val pageDropApiClient: PageDropApiClient
) : ViewModel() {

    data class ConnectionState(
        val host: String = "",
        val port: String = "22",
        val username: String = "root",
        val password: String = "",
        val targetDirectory: String = "/mnt/us/documents",
        val triggerRescan: Boolean = true,
        val serverAvailable: Boolean = false,
        val isTestingServer: Boolean = false,
        val serverTestResult: Result<Unit>? = null,
        val isTesting: Boolean = false,
        val testResult: Result<Unit>? = null
    )

    private val _state = MutableStateFlow(
        ConnectionState(
            host = kindleSettings.host,
            port = kindleSettings.port.toString(),
            username = kindleSettings.username,
            password = kindleSettings.password,
            targetDirectory = kindleSettings.targetDirectory,
            triggerRescan = kindleSettings.triggerRescan,
            serverAvailable = serverSettings.supabaseUrl.isNotBlank() && serverSettings.supabaseAnonKey.isNotBlank()
        )
    )
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun updateHost(value: String) { _state.update { it.copy(host = value) }; kindleSettings.host = value }
    fun updatePort(value: String) { _state.update { it.copy(port = value) }; kindleSettings.port = value.toIntOrNull() ?: 22 }
    fun updateUsername(value: String) { _state.update { it.copy(username = value) }; kindleSettings.username = value }
    fun updatePassword(value: String) { _state.update { it.copy(password = value) }; kindleSettings.password = value }
    fun updateTargetDirectory(value: String) { _state.update { it.copy(targetDirectory = value) }; kindleSettings.targetDirectory = value }
    fun updateTriggerRescan(value: Boolean) { _state.update { it.copy(triggerRescan = value) }; kindleSettings.triggerRescan = value }
    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testResult = null) }
            val s = _state.value
            val result = KindleSftpClient.testConnection(
                host = s.host, port = s.port.toIntOrNull() ?: 22,
                user = s.username, pass = s.password
            )
            _state.update { it.copy(isTesting = false, testResult = result) }
        }
    }

    fun testServer() {
        viewModelScope.launch {
            _state.update { it.copy(isTestingServer = true, serverTestResult = null) }
            val result = pageDropApiClient.testConnection()
            _state.update { it.copy(isTestingServer = false, serverTestResult = result) }
        }
    }
}
