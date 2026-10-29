package app.pagedrop.ui.book

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrix
import org.junit.Assert.assertEquals
import org.junit.Test

class PosterDuotoneTest {
    private val paper = Color(0.9f, 0.8f, 0.6f)
    private val ink = Color(0.1f, 0.2f, 0.3f)

    /** Applies the matrix to an rgba colour the way the Android filter does (components 0..1, offsets 0..255). */
    private fun apply(m: ColorMatrix, r: Float, g: Float, b: Float, a: Float): FloatArray {
        val v = m.values
        fun row(i: Int) = (v[i * 5] * r + v[i * 5 + 1] * g + v[i * 5 + 2] * b + v[i * 5 + 3] * a + v[i * 5 + 4] / 255f)
        return floatArrayOf(row(0), row(1), row(2), row(3))
    }

    private fun assertColor(expected: Color, actual: FloatArray, alpha: Float = 1f) {
        assertEquals(expected.red, actual[0], 0.001f)
        assertEquals(expected.green, actual[1], 0.001f)
        assertEquals(expected.blue, actual[2], 0.001f)
        assertEquals(alpha, actual[3], 0.001f)
    }

    @Test fun whiteBecomesPaper() = assertColor(paper, apply(posterDuotoneMatrix(paper, ink), 1f, 1f, 1f, 1f))

    @Test fun blackBecomesInk() = assertColor(ink, apply(posterDuotoneMatrix(paper, ink), 0f, 0f, 0f, 1f))

    @Test fun midGreyBecomesTheMidpoint() {
        val out = apply(posterDuotoneMatrix(paper, ink), 0.5f, 0.5f, 0.5f, 1f)
        assertEquals((paper.red + ink.red) / 2, out[0], 0.01f)
        assertEquals((paper.green + ink.green) / 2, out[1], 0.01f)
        assertEquals((paper.blue + ink.blue) / 2, out[2], 0.01f)
    }

    @Test fun alphaIsPreserved() =
        assertColor(paper, apply(posterDuotoneMatrix(paper, ink), 1f, 1f, 1f, 0.4f), alpha = 0.4f)
}
