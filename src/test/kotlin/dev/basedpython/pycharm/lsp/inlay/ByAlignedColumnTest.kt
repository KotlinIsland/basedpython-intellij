package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.InlayContentListener
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.util.EditorUtil
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
     * A two-line block whose first line carries [hint]: the offsets of each gap and `=`, and the
     * display columns `by` reports for each gap.
     */
    private data class Block(
        val text: String,
        val gapStart1: Int,
        val equals1: Int,
        val startColumn1: Int,
        val endColumn1: Int,
        val gapStart2: Int,
        val equals2: Int,
        val startColumn2: Int,
        val endColumn2: Int,
        /** What the first line actually infers, and the reason narrowing alone could never do it. */
        val hint: String = ": list[int]",
    )

    /**
     * The block that started this.
     *
     * ```
     * a     = [1, 2]    a at 0, gap 1..6, = at 6
     * basdf = 1         basdf at 15, gap 20..21, = at 21
     * ```
     *
     * ASCII only, so every character is one column and the offsets and columns coincide.
     */
    private val ascii = Block(
        text = "a     = [1, 2]\nbasdf = 1\n",
        gapStart1 = 1, equals1 = 6, startColumn1 = 1, endColumn1 = 6,
        gapStart2 = 20, equals2 = 21, startColumn2 = 5, endColumn2 = 6,
    )

    /**
     * Two wide characters before the gap: two characters, four columns. The columns are what `by`
     * answers for this text.
     *
     * ```
     * 名前 = [1, 2]    gap 2..3 at columns 4..5
     * abcd = 1         gap 16..17 at columns 4..5
     * ```
     */
    private val wide = Block(
        text = "名前 = [1, 2]\nabcd = 1\n",
        gapStart1 = 2, equals1 = 3, startColumn1 = 4, endColumn1 = 5,
        gapStart2 = 16, equals2 = 17, startColumn2 = 4, endColumn2 = 5,
    )

    /**
     * A tab before the gap, which at a tab size of four reaches column four from column two. The
     * columns are what `by` answers for this text with a `tabSize` of 4.
     *
     * ```
     * x:<tab>list[int] = [1]    gap 12..13 at columns 13..14
     * abcdefghijklm = [2]       gap 32..33 at columns 13..14
     * ```
     */
    private val tabbed = Block(
        text = "x:\tlist[int] = [1]\nabcdefghijklm = [2]\n",
        gapStart1 = 12, equals1 = 13, startColumn1 = 13, endColumn1 = 14,
        gapStart2 = 32, equals2 = 33, startColumn2 = 13, endColumn2 = 14,
    )

    private fun editor(block: Block = ascii): Editor {
        fixture.configureByText("a.txt", block.text)
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
    private fun laidOut(mode: ByHintMode, block: Block = ascii): Editor {
        val editor = editor(block)
        val column = ByAlignedColumn(editor)
        // As the collector seats a member: the columns `by` reported, not offsets.
        val hinted = column.seat(
            leadColumns = block.startColumn1,
            gapColumns = block.endColumn1 - block.startColumn1,
        )
        val bare = column.seat(
            leadColumns = block.startColumn2,
            gapColumns = block.endColumn2 - block.startColumn2,
        )

        val hint = ByInlayHintPresentation(
            editor = editor,
            text = block.hint,
            padLeft = false,
            padRight = false,
            mode = mode,
            pushKey = ByPushKey.CTRL_ALT,
        )
        hinted.take(hint)
        val spacer = bare.standIn()

        draw(editor, block.gapStart1, PresentationRenderer(hint))
        draw(editor, block.gapStart2, PresentationRenderer(spacer))
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
        assertEquals(editor.columnOf(ascii.equals1), editor.columnOf(ascii.equals2))
    }

    @Test
    fun `a hint wider than the padding leaves both equals signs on one column`() {
        val editor = laidOut(ByHintMode.ALWAYS)
        assertEquals(
            editor.columnOf(ascii.equals1),
            editor.columnOf(ascii.equals2),
            "the block came apart under a hint that is wider than the padding it went into",
        )
    }

    @Test
    fun `the block still moves right, since the hint has to go somewhere`() {
        // Alignment is kept, not the original column: eleven columns of hint do not fit in five of
        // padding, so the whole block has to give way — which is the half a client cannot do by
        // narrowing a hint.
        val plain = editor().columnOf(ascii.equals1)
        assertTrue(
            laidOut(ByHintMode.ALWAYS).columnOf(ascii.equals1) > plain,
            "the hint was drawn without the line making room for it",
        )
    }

    @Test
    fun `letting the push key up puts the block back where it was written`() {
        val editor = laidOut(ByHintMode.ON_PUSH)
        assertEquals(
            editor.columnOf(ascii.equals1),
            editor.columnOf(ascii.equals2),
            "a hidden hint and a spacer cost their lines different amounts",
        )
    }

    @Test
    fun `pressing it lines the block up again around the hint`() {
        val editor = laidOut(ByHintMode.ON_PUSH)
        val resting = editor.columnOf(ascii.equals1)

        hold(ctrlAlt)
        assertEquals(
            editor.columnOf(ascii.equals1),
            editor.columnOf(ascii.equals2),
            "the block came apart while the key was held",
        )
        assertTrue(editor.columnOf(ascii.equals1) > resting, "the hint took no room when it appeared")

        hold(0)
        assertEquals(resting, editor.columnOf(ascii.equals1), "the block did not come back")
        assertEquals(
            editor.columnOf(ascii.equals1),
            editor.columnOf(ascii.equals2),
            "the block came apart on the way back",
        )
    }

    /**
     * Wide characters are the case columns cannot settle to the pixel. Unicode counts one as two
     * columns, and so does `by`, but the editor draws it in whatever fallback font has the glyph —
     * 13 pixels in this editor's JetBrains Mono at 13pt, against 7.8 for a column — so the source
     * itself is not aligned on screen. What laying the block out can promise is to move every line
     * by the same amount: the hint's room is the same for both, so the `=` stay exactly as far apart
     * as the author's text put them. Counting the target in characters instead gave the wide line
     * two columns of room it does not take up, and pushed it further out than its neighbour.
     */
    @Test
    fun `a hint after wide characters moves both lines by the same amount`() {
        val plain = editor(wide)
        val resting = plain.columnOf(wide.equals1)
        val apart = plain.columnOf(wide.equals2) - resting

        val editor = laidOut(ByHintMode.ALWAYS, wide)
        assertTrue(editor.columnOf(wide.equals1) > resting, "the hint took no room")
        assertEquals(
            apart,
            editor.columnOf(wide.equals2) - editor.columnOf(wide.equals1),
            "the block moved one line further than the other",
        )
    }

    @Test
    fun `a tab before the gap is aligned to begin with at the tab size by was told`() {
        val editor = editor(tabbed)
        assertEquals(4, EditorUtil.getTabSize(editor), "the columns of the tabbed block are `by`'s at a tab size of four")
        assertEquals(editor.columnOf(tabbed.equals1), editor.columnOf(tabbed.equals2))
    }

    @Test
    fun `a hint after a tab leaves both equals signs on one column`() {
        val editor = laidOut(ByHintMode.ALWAYS, tabbed)
        assertEquals(
            editor.columnOf(tabbed.equals1),
            editor.columnOf(tabbed.equals2),
            "the block came apart around a line with a tab before its gap",
        )
    }
}
