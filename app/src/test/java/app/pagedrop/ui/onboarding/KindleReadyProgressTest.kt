package app.pagedrop.ui.onboarding

import app.pagedrop.data.NullSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KindleReadyProgressTest {

    @Test
    fun nextUndoneStep_isFirstGap() {
        assertEquals(0, nextUndoneStep(emptySet()))
        assertEquals(2, nextUndoneStep(setOf(0, 1, 4)))
        assertEquals(6, nextUndoneStep(setOf(0, 1, 2, 3, 4, 5)))
    }

    @Test
    fun nextUndoneStep_nullWhenAllDone() {
        assertNull(nextUndoneStep((0 until KINDLE_READY_STEP_COUNT).toSet()))
    }

    @Test
    fun progress_persistsAcrossInstances() {
        val prefs = NullSharedPreferences()
        val first = KindleReadyProgress(prefs)
        first.setDone(0, true)
        first.setDone(3, true)
        first.setDone(0, false)
        first.firmware = "5.16.2"
        val second = KindleReadyProgress(prefs)
        assertEquals(setOf(3), second.doneSteps)
        assertEquals("5.16.2", second.firmware)
    }

    @Test
    fun progress_ignoresOutOfRangeValues() {
        val prefs = NullSharedPreferences()
        prefs.edit().putString("done_steps", "1,99,x,-2").apply()
        assertEquals(setOf(1), KindleReadyProgress(prefs).doneSteps)
    }
}
