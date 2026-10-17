package app.pagedrop.ui.onboarding

import android.content.Context
import android.content.SharedPreferences
import app.pagedrop.data.NullSharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

const val KINDLE_READY_STEP_COUNT = 7

/** Index of the first step not yet ticked, or null when every step is done. */
fun nextUndoneStep(done: Set<Int>, stepCount: Int = KINDLE_READY_STEP_COUNT): Int? =
    (0 until stepCount).firstOrNull { it !in done }

/** Local-only progress for the "Get your Kindle ready" walkthrough. */
@Singleton
class KindleReadyProgress internal constructor(private val prefs: SharedPreferences) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        try {
            context.getSharedPreferences("kindle_ready", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            NullSharedPreferences()
        }
    )

    internal constructor() : this(NullSharedPreferences())

    var doneSteps: Set<Int>
        get() = prefs.getString(KEY_DONE, "").orEmpty()
            .split(',').mapNotNull { it.trim().toIntOrNull() }
            .filter { it in 0 until KINDLE_READY_STEP_COUNT }.toSet()
        private set(value) {
            prefs.edit().putString(KEY_DONE, value.sorted().joinToString(",")).apply()
        }

    fun setDone(step: Int, done: Boolean) {
        doneSteps = if (done) doneSteps + step else doneSteps - step
    }

    /** Firmware text the user typed, kept only so they can copy it into the wizard. */
    var firmware: String
        get() = prefs.getString(KEY_FIRMWARE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FIRMWARE, value).apply()

    private companion object {
        const val KEY_DONE = "done_steps"
        const val KEY_FIRMWARE = "firmware"
    }
}
