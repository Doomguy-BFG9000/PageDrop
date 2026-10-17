package app.pagedrop.ui.tools.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.KindleSettings
import app.pagedrop.tools.dashboard.DeviceCommandRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val kindleSettings: KindleSettings
) : ViewModel() {

    data class DashboardState(
        val isLoading: Boolean = false,
        val deviceStatus: DeviceCommandRunner.DeviceStatus? = null,
        val lastResult: DeviceCommandRunner.CommandResult? = null,
        val error: String? = null,
        /** null = not checked yet, true = answered on the SSH port, false = could not connect. */
        val reachable: Boolean? = null,
        val isConfigured: Boolean = true,
        /** The real exception from the TCP probe when [reachable] is false. */
        val probeError: Throwable? = null
    )

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    val host: String get() = kindleSettings.host
    val port: Int get() = kindleSettings.port

    val repairRecipes = DeviceCommandRunner.repairRecipes

    fun refresh() {
        viewModelScope.launch {
            if (!kindleSettings.isConfigured) {
                _state.update { DashboardState(isConfigured = false) }
                return@launch
            }
            _state.update { it.copy(isLoading = true, error = null, lastResult = null, isConfigured = true) }
            val probeError: Throwable? = withContext(Dispatchers.IO) {
                try {
                    Socket().use { it.connect(InetSocketAddress(kindleSettings.host, kindleSettings.port), 2_000) }
                    null
                } catch (e: Exception) {
                    e
                }
            }
            if (probeError != null) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        reachable = false,
                        probeError = probeError,
                        deviceStatus = null,
                        error = "Can't reach ${kindleSettings.host}:${kindleSettings.port}."
                    )
                }
                return@launch
            }
            try {
                val status = DeviceCommandRunner.getDeviceStatus(
                    host = kindleSettings.host,
                    port = kindleSettings.port,
                    user = kindleSettings.username,
                    pass = kindleSettings.password
                )
                _state.update { it.copy(isLoading = false, reachable = true, probeError = null, deviceStatus = status) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        reachable = true,
                        error = "Failed to fetch device status: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun triggerRescan() {
        viewModelScope.launch {
            val result = DeviceCommandRunner.triggerRescan(
                kindleSettings.host, kindleSettings.port,
                kindleSettings.username, kindleSettings.password
            )
            _state.update { it.copy(lastResult = result, error = result.stderr.ifBlank { null }) }
        }
    }

    fun reboot() {
        viewModelScope.launch {
            DeviceCommandRunner.rebootDevice(
                kindleSettings.host, kindleSettings.port,
                kindleSettings.username, kindleSettings.password
            )
        }
    }

    fun runRepair(recipe: DeviceCommandRunner.RepairRecipe) {
        viewModelScope.launch {
            val result = DeviceCommandRunner.runRepair(
                recipe = recipe,
                host = kindleSettings.host,
                port = kindleSettings.port,
                user = kindleSettings.username,
                pass = kindleSettings.password
            )
            _state.update { it.copy(lastResult = result, error = result.stderr.ifBlank { null }) }
        }
    }
}
