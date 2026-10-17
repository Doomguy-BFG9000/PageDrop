package app.pagedrop.ui.onboarding

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

@HiltViewModel
class KindleReadyViewModel @Inject constructor(
    private val progress: KindleReadyProgress
) : ViewModel() {

    data class State(val done: Set<Int>, val firmware: String)

    private val _state = MutableStateFlow(State(progress.doneSteps, progress.firmware))
    val state: StateFlow<State> = _state.asStateFlow()

    fun setDone(step: Int, done: Boolean) {
        progress.setDone(step, done)
        _state.update { it.copy(done = progress.doneSteps) }
    }

    fun setFirmware(value: String) {
        progress.firmware = value
        _state.update { it.copy(firmware = value) }
    }
}
