package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.editor.impl.InterLineShiftAnimator
import java.awt.Component
import java.util.concurrent.atomic.AtomicReference

/**
 * Opens the gap between two lines that the "Add Log" affordance sits in.
 *
 * This is not decoration, which is what made leaving it out such an expensive mistake: an
 * [com.intellij.openapi.editor.impl.InterLineBreakpointConfiguration] whose animator is null makes
 * `EditorGutterComponentImpl.updateInterLineShiftState` call `stopCurrentAnimator()` and give up, so
 * the gutter never opens a gap, never reserves a hit area, and paints nothing — while the extension
 * itself reports that it offered a perfectly good configuration. The animator is the thing that
 * physically makes room.
 *
 * The contract is the whole of [InterLineShiftAnimator]: while a shift is running, the line above
 * the gap is drawn `shift` pixels higher and the line below `shift` pixels lower, so the visible gap
 * is twice that. IDEA's version eases `currentShift` from 0 to the target with a `JBAnimator`; this
 * one jumps straight there. The gap opens on hover either way — a snap rather than a glide.
 */
class ByInterLineShift(private val component: Component) : InterLineShiftAnimator {

    private data class Shift(val lineAbove: Int, val lineBelow: Int, val pixels: Int)

    private val state = AtomicReference<Shift?>(null)

    override fun getShiftForVisualLine(visualLine: Int): Int {
        val shift = state.get() ?: return 0
        return when (visualLine) {
            shift.lineAbove -> -shift.pixels
            shift.lineBelow -> shift.pixels
            else -> 0
        }
    }

    override fun startShift(lineAbove: Int, lineBelow: Int, targetShift: Int) {
        val current = state.get()
        if (current != null &&
            current.lineAbove == lineAbove &&
            current.lineBelow == lineBelow &&
            current.pixels == targetShift
        ) {
            return
        }
        state.set(Shift(lineAbove, lineBelow, targetShift))
        component.repaint()
    }

    override fun stopShift() {
        if (state.getAndSet(null) != null) component.repaint()
    }
}
