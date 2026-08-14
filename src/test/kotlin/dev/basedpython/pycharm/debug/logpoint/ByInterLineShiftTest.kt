package dev.basedpython.pycharm.debug.logpoint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import javax.swing.JPanel

/**
 * The shift contract, which is the whole reason the gutter opens a gap at all.
 *
 * `EditorGutterComponentImpl` gives up on a configuration whose animator is null — `stopCurrentAnimator()`
 * and return — so a gap with no animator is invisible no matter how correct everything else is. The
 * numbers matter too: the line above moves up and the line below moves down, and getting the sign
 * wrong would close the gap rather than open it.
 */
class ByInterLineShiftTest {

    private val shift = ByInterLineShift(JPanel())

    @Test
    fun `nothing is shifted until a shift starts`() {
        assertEquals(0, shift.getShiftForVisualLine(4))
    }

    @Test
    fun `the line above moves up and the line below moves down`() {
        shift.startShift(4, 5, 6)

        assertEquals(-6, shift.getShiftForVisualLine(4), "the line above the gap moves up")
        assertEquals(6, shift.getShiftForVisualLine(5), "the line below the gap moves down")
    }

    @Test
    fun `lines away from the gap do not move`() {
        shift.startShift(4, 5, 6)

        assertEquals(0, shift.getShiftForVisualLine(3))
        assertEquals(0, shift.getShiftForVisualLine(6))
    }

    @Test
    fun `stopping closes the gap everywhere`() {
        shift.startShift(4, 5, 6)
        shift.stopShift()

        assertEquals(0, shift.getShiftForVisualLine(4))
        assertEquals(0, shift.getShiftForVisualLine(5))
    }

    @Test
    fun `moving the gap to another pair of lines releases the old one`() {
        shift.startShift(4, 5, 6)
        shift.startShift(9, 10, 6)

        assertEquals(0, shift.getShiftForVisualLine(4))
        assertEquals(0, shift.getShiftForVisualLine(5))
        assertEquals(-6, shift.getShiftForVisualLine(9))
        assertEquals(6, shift.getShiftForVisualLine(10))
    }
}
