package app.pagedrop.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A single file shared to PageDrop that is waiting for the Library's Add book sheet.
 * Held app-wide so it survives the share arriving before the Library tab is on screen.
 */
@Singleton
class PendingSharedFile @Inject constructor() {
    private val _uri = MutableStateFlow<Uri?>(null)
    val uri: StateFlow<Uri?> = _uri.asStateFlow()

    fun offer(uri: Uri) {
        _uri.value = uri
    }

    /** Called by the Library once it has opened the sheet for [uri]. */
    fun consume(uri: Uri) {
        _uri.compareAndSet(uri, null)
    }
}
