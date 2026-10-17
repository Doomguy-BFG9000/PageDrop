package app.pagedrop.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.DEFAULT_PORT
import app.pagedrop.data.KindleSettings
import app.pagedrop.transfer.discovery.DiscoveredDevice
import app.pagedrop.transfer.discovery.KindleDiscovery
import app.pagedrop.transfer.discovery.ScanEvent
import app.pagedrop.transfer.sftp.KindleSftpClient
import app.pagedrop.ui.connection.ConnectionHints
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val kindleSettings: KindleSettings,
    private val discovery: KindleDiscovery
) : ViewModel() {

    data class SetupState(
        val host: String = "",
        val port: String = DEFAULT_PORT.toString(),
        val username: String = "root",
        val password: String = "",
        val isScanning: Boolean = false,
        val scanProgress: Float = 0f,
        val scanFinished: Boolean = false,
        val scanError: String? = null,
        val devices: List<DiscoveredDevice> = emptyList(),
        val isTesting: Boolean = false,
        val testSuccess: Boolean? = null,
        val testHint: ConnectionHints.Hint? = null
    ) {
        val canSave get() = host.isNotBlank() && port.toIntOrNull() != null
    }

    private val _state = MutableStateFlow(
        SetupState(
            host = kindleSettings.host,
            port = kindleSettings.port.toString(),
            username = kindleSettings.username,
            password = kindleSettings.password
        )
    )
    val state: StateFlow<SetupState> = _state.asStateFlow()

    private var scanJob: Job? = null

    fun updateHost(value: String) = _state.update { it.copy(host = value.trim(), testSuccess = null, testHint = null) }
    fun updatePort(value: String) = _state.update { it.copy(port = value, testSuccess = null, testHint = null) }
    fun updateUsername(value: String) = _state.update { it.copy(username = value, testSuccess = null, testHint = null) }
    fun updatePassword(value: String) = _state.update { it.copy(password = value, testSuccess = null, testHint = null) }

    fun findMyKindle() {
        if (scanJob?.isActive == true) return
        _state.update {
            it.copy(isScanning = true, scanProgress = 0f, scanFinished = false, scanError = null, devices = emptyList())
        }
        scanJob = viewModelScope.launch {
            if (discovery.localIpv4() == null) {
                _state.update {
                    it.copy(isScanning = false, scanError = "Connect this phone to Wi-Fi first, then try again.")
                }
                return@launch
            }
            try {
                discovery.scan().collect { event ->
                    when (event) {
                        is ScanEvent.Progress -> _state.update {
                            it.copy(scanProgress = if (event.total == 0) 0f else event.done / event.total.toFloat())
                        }
                        is ScanEvent.Found -> _state.update { s ->
                            val list = (s.devices + event.device)
                                .sortedWith(compareByDescending<DiscoveredDevice> { it.looksLikeKindle }.thenBy { it.host })
                            s.copy(devices = list)
                        }
                    }
                }
                _state.update { it.copy(isScanning = false, scanFinished = true, scanProgress = 1f) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(isScanning = false, scanError = "Scan failed: ${e.localizedMessage}") }
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        _state.update { it.copy(isScanning = false) }
    }

    fun pick(device: DiscoveredDevice) {
        _state.update {
            it.copy(host = device.host, port = device.port.toString(), testSuccess = null, testHint = null)
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            _state.update { it.copy(isTesting = true, testSuccess = null, testHint = null) }
            val s = _state.value
            val port = s.port.toIntOrNull() ?: DEFAULT_PORT
            val result = KindleSftpClient.testConnection(s.host, port, s.username, s.password)
            _state.update {
                it.copy(
                    isTesting = false,
                    testSuccess = result.isSuccess,
                    testHint = result.exceptionOrNull()?.let { e -> ConnectionHints.explain(e, port) }
                )
            }
        }
    }

    fun save() {
        val s = _state.value
        kindleSettings.host = s.host.trim()
        kindleSettings.port = s.port.toIntOrNull() ?: DEFAULT_PORT
        kindleSettings.username = s.username.ifBlank { "root" }
        kindleSettings.password = s.password
        kindleSettings.setupSkipped = false
    }

    fun skip() {
        kindleSettings.setupSkipped = true
    }

    override fun onCleared() {
        scanJob?.cancel()
    }
}
