package app.pagedrop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.data.KindleSettings
import app.pagedrop.metadata.MetadataSettings
import app.pagedrop.server.PageDropApiClient
import app.pagedrop.server.ServerSettings
import app.pagedrop.server.ServerUploadConsent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val kindleSettings: KindleSettings,
    private val serverSettings: ServerSettings,
    private val pageDropApiClient: PageDropApiClient,
    private val uploadConsent: ServerUploadConsent,
    private val metadataSettings: MetadataSettings,
) : ViewModel() {

    /** Whether Google Books is asked for titles, authors and covers (Open Library always is). */
    val useGoogleBooks: StateFlow<Boolean> = metadataSettings.useGoogleBooks

    fun updateUseGoogleBooks(value: Boolean) = metadataSettings.setUseGoogleBooks(value)

    data class SettingsState(
        val kindleSummary: String = "",
        /** One folder per line; the first line is the default. */
        val folders: String = "",
        /** True while [folders] holds edits that have not been saved yet. */
        val foldersDirty: Boolean = false,
        val triggerRescan: Boolean = true,
        val serverAvailable: Boolean = false,
        val useServer: Boolean = true,
        val isTestingServer: Boolean = false,
        val serverTestResult: Result<Unit>? = null
    )

    private val _state = MutableStateFlow(load())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun load() = SettingsState(
        kindleSummary = summary(),
        folders = kindleSettings.targetDirectories.joinToString("\n"),
        triggerRescan = kindleSettings.triggerRescan,
        serverAvailable = serverSettings.hasBuiltInServer,
        useServer = serverSettings.useServer
    )

    private fun summary() =
        if (kindleSettings.isConfigured) "${kindleSettings.host}:${kindleSettings.port}" else "Not set up"

    /** Refresh values that other screens (Connection) may have changed. */
    fun refresh() = _state.update { it.copy(kindleSummary = summary()) }

    /** Edits stay local until [saveFolders]; half-typed paths are never persisted. */
    fun updateFolders(value: String) {
        _state.update { it.copy(folders = value, foldersDirty = true) }
    }

    fun saveFolders() {
        if (!_state.value.foldersDirty) return
        kindleSettings.targetDirectories = _state.value.folders.lines()
        _state.update {
            it.copy(folders = kindleSettings.targetDirectories.joinToString("\n"), foldersDirty = false)
        }
    }

    override fun onCleared() {
        saveFolders()
    }

    fun updateTriggerRescan(value: Boolean) {
        _state.update { it.copy(triggerRescan = value) }
        kindleSettings.triggerRescan = value
    }

    fun updateUseServer(value: Boolean) {
        // The switch's description explains the upload, so flipping it also answers the first-upload notice.
        uploadConsent.answer(value)
        _state.update { it.copy(useServer = value, serverTestResult = null) }
    }

    fun testServer() {
        viewModelScope.launch {
            _state.update { it.copy(isTestingServer = true, serverTestResult = null) }
            val result = pageDropApiClient.testConnection()
            _state.update { it.copy(isTestingServer = false, serverTestResult = result) }
        }
    }
}
