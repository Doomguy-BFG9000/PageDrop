package app.pagedrop.ui.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.KindleSettings
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
    val kindleSettings: KindleSettings
) : ViewModel() {

    data class ConnectionState(
        val host: String = "",
        val port: String = "2222",
        val username: String = "root",
        val password: String = "",
        val isTesting: Boolean = false,
        val testResult: Result<Unit>? = null,
        val testedPort: Int = 2222
    )

    private val _state = MutableStateFlow(
        ConnectionState(
            host = kindleSettings.host,
            port = kindleSettings.port.toString(),
            username = kindleSettings.username,
            password = kindleSettings.password
        )
    )
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    fun updateHost(value: String) { _state.update { it.copy(host = value.trim()) }; kindleSettings.host = value.trim() }
    fun updatePort(value: String) {
        _state.update { it.copy(port = value) }
        kindleSettings.port = value.toIntOrNull() ?: app.pagedrop.data.DEFAULT_PORT
    }
    fun updateUsername(value: String) { _state.update { it.copy(username = value) }; kindleSettings.username = value }
    fun updatePassword(value: String) { _state.update { it.copy(password = value) }; kindleSettings.password = value }

    fun testConnection() {
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
}
