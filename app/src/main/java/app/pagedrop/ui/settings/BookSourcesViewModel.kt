package app.pagedrop.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pagedrop.discover.OpdsCatalog
import app.pagedrop.discover.SourceKind
import app.pagedrop.discover.SourceSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Settings -> Book sources: built-in toggles and the user's OPDS catalogs. */
@HiltViewModel
class BookSourcesViewModel @Inject constructor(
    private val settings: SourceSettings,
) : ViewModel() {

    val enabledBuiltIns: StateFlow<Set<SourceKind>> = settings.enabledBuiltIns
    val catalogs: StateFlow<List<OpdsCatalog>> = settings.catalogs

    data class AddState(val adding: Boolean = false, val error: String? = null)

    private val _add = MutableStateFlow(AddState())
    val add: StateFlow<AddState> = _add.asStateFlow()

    fun setEnabled(kind: SourceKind, on: Boolean) = settings.setEnabled(kind, on)

    fun removeCatalog(id: String) = settings.removeCatalog(id)

    fun clearAddError() = _add.update { it.copy(error = null) }

    /** Validates the feed; [onAdded] runs only when the catalog was saved. */
    fun addCatalog(url: String, username: String, password: String, onAdded: () -> Unit) {
        val cleanUrl = url.trim()
        if (cleanUrl.isEmpty()) {
            _add.value = AddState(error = "Enter the catalog’s web address.")
            return
        }
        if (_add.value.adding) return
        _add.value = AddState(adding = true)
        viewModelScope.launch {
            val result = try {
                settings.addCatalog(
                    cleanUrl,
                    username.trim().takeIf { it.isNotEmpty() },
                    password.takeIf { it.isNotEmpty() },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            result.onSuccess {
                _add.value = AddState()
                onAdded()
            }.onFailure {
                _add.value = AddState(error = catalogErrorMessage(it))
            }
        }
    }
}

internal fun catalogErrorMessage(error: Throwable): String = when (error) {
    is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
        "Couldn’t reach that address. Check the web address and your connection."
    else -> error.message?.takeIf { it.length in 1..140 }
        ?: "That doesn’t look like an OPDS catalog. Check the address (and username and password, if it needs them)."
}
