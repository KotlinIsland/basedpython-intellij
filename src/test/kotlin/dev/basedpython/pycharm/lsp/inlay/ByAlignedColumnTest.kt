package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.InlayContentListener
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JPanel

/**
 * Where the `=` signs actually land, asked of a real editor with real inlays in it.
 *
 * [ByAlignmentTest] settles the arithmetic; this settles that the arithmetic is wired to something.
 * Everything between the two — a width reported smaller than the glyphs drawn, an empty inlay
 * standing in for a hint, the pixel the editor charges for an inline element that is standing by —
 * only shows up as a coordinate, so a coordinate is what is asserted. `Editor.offsetToXY` is the
 * editor's own answer to "where is this character", inlays and all.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByAlignedColumnTest {

    private val fixture by codeInsightFixture()

    private val source = JPanel()

    private val ctrlAlt = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK

    /**
     * The block that started this, with the offsets worked out once.
     *
     * ```
     * a     = [1, 2]    a at 0, gap 1..6, = at 6
     * basdf = 1         basdf at 15, gap 20..21, = at 21
     * ```
     */
    private object Block {
        const val TEXT = "a     = [1, 2]\nbasdf = 1\n"
        const val GAP_START_1 = 1
        const val EQUALS_1 = 6
        const val LEAD_1 = 1
        const val GAP_1 = 5
        const val GAP_START_2 = 20
        const val EQUALS_2 = 21
        const val LEAD_2 = 5
        const val GAP_2 = 1

        /** What `a = [1, 2]` actually infers, and the reason narrowing alone could never do it. */
        const val HINT = ": list[int]"
    }

    private fun editor(): Editor {
        fixture.configureByText("a.txt", Block.TEXT)
        return fixture.editor
    }

    private fun hold(modifiersEx: Int) {
        ByHintPush.getInstance().onEvent(
            KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiersEx, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED),
        )
    }

    /** The push state is the application's, and so is shared with whatever ran before this. */
    @BeforeEach
    fun releaseEverything() {
        hold(0)
    }

    /** Lays the block out the way the collector does, and hands back the editor. */
    private fun laidOut(mode: ByHintMode): Editor {
        val editor = editor()
        val column = ByAlignedColumn(editor)
        val hinted = column.seat(leadColumns = Block.LEAD_1, gapColumns = Block.GAP_1)
        val bare = column.seat(leadColumns = Block.LEAD_2, gapColumns = Block.GAP_2)

        val hint = ByInlayHintPresentation(
            editor = editor,
            text = Block.HINT,
            padLeft = false,
            padRight = false,
            mode = mode,
            pushKey = ByPushKey.CTRL_ALT,
        )
        hinted.take(hint)
        val spacer = bare.standIn()

        draw(editor, Block.GAP_START_1, PresentationRenderer(hint))
        draw(editor, Block.GAP_START_2, PresentationRenderer(spacer))
        // As the collector does: the block watches the key, not only the hints in it. A hint appearing
        // moves every line, and a spacer has no mode of its own to hear about it through.
        if (column.watchesPush()) ByHintPush.getInstance().watch(column)
        return editor
    }

    /**
     * Adds an inlay the way `InlayHintsPass` does, listener and all.
     *
     * The listener is the half that is easy to leave out and impossible to notice: without it a
     * presentation can fire every size change it likes and the inlay keeps the width it was first
     * measured at, so a push-to-hint block would look frozen rather than broken.
     */
    private fun draw(editor: Editor, offset: Int, renderer: PresentationRenderer) {
        val inlay = editor.inlayModel.addInlineElement(offset, true, renderer)
            ?: error("the editor refused an inlay at $offset")
        renderer.presentation.addListener(InlayContentListener(inlay))
    }

    private fun Editor.columnOf(offset: Int): Int = offsetToXY(offset).x

    @Test
    fun `the source this is all about is aligned to begin with`() {
        // Not a tautology: everything below compares two x coordinates, and would pass vacuously in a
        // proportional font where the two lines never lined up in the first place.
        val editor = editor()
        assertEquals(editor.columnOf(Block.EQUALS_1), editor.columnOf(Block.EQUALS_2))
    }

    @Test
    fun `a hint wider than the padding leaves both equals signs on one column`() {
        val editor = laidOut(ByHintMode.ALWAYS)
        assertEquals(
            editor.columnOf(Block.EQUALS_1),
            editor.columnOf(Block.EQUALS_2),
            "the block came apart under a hint that is wider than the padding it went into",
        )
    }

    @Test
    fun `the block still moves right, since the hint has to go somewhere`() {
        // Alignment is kept, not the original column: eleven columns of hint do not fit in five of
        // padding, so the whole block has to give way — which is the half a client cannot do by
        // narrowing a hint.
        val plain = editor().columnOf(Block.EQUALS_1)
        assertTrue(
            laidOut(ByHintMode.ALWAYS).columnOf(Block.EQUALS_1) > plain,
            "the hint was drawn without the line making room for it",
        )
    }

    @Test
    fun `letting the push key up puts the block back where it was written`() {
        val editor = laidOut(ByHintMode.ON_PUSH)
        assertEquals(
            editor.columnOf(Block.EQUALS_1),
            editor.columnOf(Block.EQUALS_2),
            "a hidden hint and a spacer cost their lines different amounts",
        )
    }

    @Test
    fun `pressing it lines the block up again around the hint`() {
        val editor = laidOut(ByHintMode.ON_PUSH)
        val resting = editor.columnOf(Block.EQUALS_1)

        hold(ctrlAlt)
        assertEquals(
            editor.columnOf(Block.EQUALS_1),
            editor.columnOf(Block.EQUALS_2),
            "the block came apart while the key was held",
        )
        assertTrue(editor.columnOf(Block.EQUALS_1) > resting, "the hint took no room when it appeared")

        hold(0)
        assertEquals(resting, editor.columnOf(Block.EQUALS_1), "the block did not come back")
        assertEquals(
            editor.columnOf(Block.EQUALS_1),
            editor.columnOf(Block.EQUALS_2),
            "the block came apart on the way back",
        )
    }
}
