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
     * A two-line block whose first line carries [hint]: where each line's gap starts and where its
     * `=` is, which is the whole of what `by` answers that a seat is built from.
     */
    private data class Block(
        val text: String,
        val gapStart1: Int,
        val equals1: Int,
        val gapStart2: Int,
        val equals2: Int,
        /** What the first line actually infers, and the reason narrowing alone could never do it. */
        val hint: String = ": list[int]",
    ) {
        /**
         * The line's own text from its first character up to its gap, which is what a seat holds.
         *
         * Both blocks are two lines, so the second one starts after the only line break before it.
         */
        fun lead(line: Int): String = when (line) {
            1 -> text.substring(0, gapStart1)
            else -> text.substring(text.indexOf('\n') + 1, gapStart2)
        }

        /** The padding the author left, which is spaces and is the rest of what a seat holds. */
        fun gap(line: Int): String = when (line) {
            1 -> text.substring(gapStart1, equals1)
            else -> text.substring(gapStart2, equals2)
        }
    }

    /**
     * The block that started this.
     *
     * ```
     * a     = [1, 2]    a at 0, gap 1..6, = at 6
     * basdf = 1         basdf at 15, gap 20..21, = at 21
     * ```
     *
     * ASCII only, so every glyph is one advance of the editor font wide and the source is already
     * square on screen.
     */
    private val ascii = Block(
        text = "a     = [1, 2]\nbasdf = 1\n",
        gapStart1 = 1, equals1 = 6,
        gapStart2 = 20, equals2 = 21,
    )

    /**
     * Two wide characters before the gap: two characters, four columns, and neither of those is what
     * the editor draws.
     *
     * ```
     * 名前 = [1, 2]    gap 2..3, four columns before it
     * abcd = 1         gap 16..17, four columns before it
     * ```
     *
     * `by` reports these two as one group because they *are* one column in the source. The editor
     * draws `名前` in whatever fallback font has the glyphs — 13 pixels each against the 7.8 of an
     * advance of JetBrains Mono — so on screen the two `=` are six pixels apart before anything is
     * hinted. This is the case a column can never settle and a measurement can.
     */
    private val wide = Block(
        text = "名前 = [1, 2]\nabcd = 1\n",
        gapStart1 = 2, equals1 = 3,
        gapStart2 = 16, equals2 = 17,
    )

    /**
     * A tab before the gap, which at a tab size of four reaches column four from column two.
     *
     * ```
     * x:<tab>list[int] = [1]    gap 12..13
     * abcdefghijklm = [2]       gap 32..33
     * ```
     *
     * The tab is measured to the next tab stop *in pixels*, as the editor draws it, so the tab size
     * that settles this block is the editor's own rather than one sent to `by` and counted back.
     */
    private val tabbed = Block(
        text = "x:\tlist[int] = [1]\nabcdefghijklm = [2]\n",
        gapStart1 = 12, equals1 = 13,
        gapStart2 = 32, equals2 = 33,
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
        // As the collector seats a member: the line's own text up to its gap, and the gap itself.
        val hinted = column.seat(lead = block.lead(1), gap = block.gap(1))
        val bare = column.seat(lead = block.lead(2), gap = block.gap(2))

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
     * Wide characters are the case a column can never settle and a measurement can.
     *
     * Unicode counts `名` as two columns and so does `by`; the editor draws it in whatever fallback
     * font has the glyph, at neither two advances nor one. The source is therefore *not* square on
     * screen to begin with — the two `=` start six pixels apart — and a layout built on columns could
     * only promise to move both lines by the same amount, leaving those six pixels wherever they
     * were. Measuring the lead in the font the editor will draw it in closes them: the column is the
     * furthest right of the measured lines, and every member is brought to it.
     */
    @Test
    fun `a block whose lead is wide characters is brought onto one column`() {
        val plain = editor(wide)
        val resting = plain.columnOf(wide.equals1)
        assertTrue(
            resting != plain.columnOf(wide.equals2),
            "this block is only interesting because its source is not square on screen",
        )

        val editor = laidOut(ByHintMode.ALWAYS, wide)
        assertEquals(
            editor.columnOf(wide.equals1),
            editor.columnOf(wide.equals2),
            "the wide line's glyphs were counted as columns rather than measured",
        )
        assertTrue(editor.columnOf(wide.equals1) > resting, "the hint took no room")
    }

    @Test
    fun `a tab before the gap is aligned to begin with at the editor's own tab size`() {
        val editor = editor(tabbed)
        assertEquals(4, EditorUtil.getTabSize(editor), "the tabbed block is only square at a tab size of four")
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
