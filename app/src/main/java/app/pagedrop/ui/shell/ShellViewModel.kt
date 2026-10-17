package app.pagedrop.ui.shell

import androidx.lifecycle.ViewModel
import app.pagedrop.data.KindleSettings
import app.pagedrop.share.PendingSharedFile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ShellViewModel @Inject constructor(
    private val kindleSettings: KindleSettings,
    pendingSharedFile: PendingSharedFile,
) : ViewModel() {
    /** A file shared to PageDrop that the Library should open in its Add book sheet. */
    val pendingSharedUri = pendingSharedFile.uri

    /** First run: no Kindle configured and the user has not chosen "Set up later". */
    val needsFirstRunSetup: Boolean
        get() = !kindleSettings.isConfigured && !kindleSettings.setupSkipped
}
