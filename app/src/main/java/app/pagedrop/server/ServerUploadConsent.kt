package app.pagedrop.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-time notice before the first book file is uploaded to the conversion server. A conversion that would use the
 * server suspends in [confirm] until the app shell answers via [answer]; the answer is remembered, and "keep on phone"
 * switches [ServerSettings.useServer] off so later conversions stay local without asking again.
 */
@Singleton
class ServerUploadConsent @Inject constructor(
    private val settings: ServerSettings,
) {
    private val lock = Any()
    private var waiting: CompletableDeferred<Boolean>? = null

    private val turnedOffListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private val _pending = MutableStateFlow(false)
    /** True while the notice should be on screen. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Whether this upload may go to the server. Asks once; afterwards follows the saved setting. */
    suspend fun confirm(): Boolean {
        if (!settings.isConfigured) return false
        val decision = synchronized(lock) {
            if (settings.uploadNoticeAnswered) return settings.isConfigured
            waiting ?: CompletableDeferred<Boolean>().also { waiting = it; _pending.value = true }
        }
        return decision.await()
    }

    fun answer(useServer: Boolean) {
        val decision = synchronized(lock) {
            settings.useServer = useServer
            settings.uploadNoticeAnswered = true
            _pending.value = false
            waiting.also { waiting = null }
        }
        decision?.complete(useServer)
        if (!useServer) turnedOffListeners.forEach { runCatching { it() } }
    }

    /** [listener] runs whenever the server is switched off (notice or Settings), e.g. to cancel in-flight requests. */
    fun addTurnedOffListener(listener: () -> Unit) {
        turnedOffListeners += listener
    }
}
