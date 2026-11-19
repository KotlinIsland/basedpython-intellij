package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.PresentationListener
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JPanel

/**
 * A hint drawn as lines of code of its own — a member the stub declares and the module lacks: its
 * size as code, its lines at their columns, push-to-hint, and clicks on what it names.
 */
@TestFixtures
class ByBlockHintPresentationTest {

    private val fixture by codeInsightFixture()

    /** A member the stub has after `f`, as `by` sends it: a blank line, then the signature at its indentation. */
    private val member = "\n    def g(self, a: int) -> None: ..."

    private val module = "class A(B):\n    def f(self, a): ...\n"

    private fun editor(): Editor {
        fixture.configureByText("mod.by", module)
        return fixture.editor
    }

    private fun hold(modifiersEx: Int) {
        ByHintPush.getInstance().onEvent(KeyEvent(JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiersEx, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED))
    }

    private val ctrlAlt = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK

    @BeforeEach
    fun releaseEverything() = onEdt { hold(0) }

    /** Where [column] of a line of code falls, as the editor lays the line out. */
    private fun Editor.columnX(column: Int): Int {
        val font = colorsScheme.getFont(EditorFontType.PLAIN)
        val metrics = contentComponent.getFontMetrics(font)
        return font.getStringBounds(" ".repeat(column), metrics.fontRenderContext).width.toInt()
    }

    @Test
    fun `it is as tall as its lines and as wide as the longest, as code`() = onEdt {
        val editor = editor()
        val block = ByBlockHintPresentation(editor, member)
        assertEquals(2 * editor.lineHeight, block.height)
        val longest = "    def g(self, a: int) -> None: ..."
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val expected = font.getStringBounds(longest, editor.contentComponent.getFontMetrics(font).fontRenderContext).width
        assertTrue(kotlin.math.abs(block.width - expected) <= 1, "${block.width} for $expected")
    }

    /**
     * Drawn in the editor at the column its own indentation puts it — through a real block inlay, below
     * the line `by` placed it under, so the claim is about where the editor shows it.
     */
    @Test
    fun `a block below a line is drawn under it, its code at the column the label indents it to`() = onEdt {
        val editor = editor()
        val block = ByBlockHintPresentation(editor, member)
        val renderer = object : EditorCustomElementRenderer {
            override fun calcWidthInPixels(inlay: Inlay<*>) = block.width
            override fun calcHeightInPixels(inlay: Inlay<*>) = block.height
            override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) = Unit
        }
        val line = editor.document.getLineEndOffset(1)
        val inlay = editor.inlayModel.addBlockElement(line, true, false, 0, renderer)!!
        assertEquals(2 * editor.lineHeight, inlay.heightInPixels)
        // the line after `f` starts below both of the block's lines
        val belowF = editor.visualLineToY(1) + editor.lineHeight
        assertEquals(belowF, inlay.bounds!!.y)
        // the `def` of the label's second line sits where `def` of the line above does
        val defX = editor.offsetToXY(module.indexOf("def")).x - inlay.bounds!!.x
        assertTrue(kotlin.math.abs(defX - editor.columnX(4)) <= 1, "def at $defX, the label's fourth column at ${editor.columnX(4)}")
    }

    @Test
    fun `tabs are laid out to the editor's tab stops`() {
        val shaped = ByBlockText.of("\tdef g(): ...\n\t\tpass", 4)
        assertEquals("    def g(): ...\n        pass", shaped.text)
        assertEquals(listOf("    def g(): ...", "        pass"), shaped.lines)
        assertEquals(listOf(0, 17), shaped.lineStarts.toList())
        // `g` was character 5 of the label, and is 8 of what is drawn
        val location = Location("file:///p/mod.pyi", Range(Position(2, 8), Position(2, 9)))
        assertEquals(ByHintLink(8, 9, location), shaped.mapLink(ByHintLink(5, 6, location)))
        assertNull(shaped.mapLink(ByHintLink(5, 99, location)))
    }

    @Test
    fun `it paints without throwing, on a line with nothing to tint`() = onEdt {
        val block = ByBlockHintPresentation(editor(), "\n\n    x: int")
        val image = BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            block.paint(g, TextAttributes())
        } finally {
            g.dispose()
        }
        assertTrue((0 until image.width).any { x -> (0 until image.height).any { y -> image.getRGB(x, y) != 0 } }, "something is drawn")
    }

    // region: push

    private class Sizes : PresentationListener {
        val changes = mutableListOf<Pair<Dimension, Dimension>>()
        override fun sizeChanged(previous: Dimension, current: Dimension) {
            changes += previous to current
        }

        override fun contentChanged(area: Rectangle) = Unit
    }

    /** A block inlay may be nothing at all, so a hidden one takes neither width nor height. */
    @Test
    fun `a push block takes no room until the key goes down, and says so each time`() = onEdt {
        val editor = editor()
        val block = ByBlockHintPresentation(editor, member, mode = ByHintMode.ON_PUSH)
        val sizes = Sizes()
        block.addListener(sizes)
        assertEquals(0, block.width)
        assertEquals(0, block.height)

        hold(ctrlAlt)
        assertEquals(2 * editor.lineHeight, block.height)
        assertTrue(block.width > 0)

        hold(0)
        assertEquals(0, block.height)
        assertEquals(listOf(0, 2 * editor.lineHeight, 0), listOf(sizes.changes[0].first.height) + sizes.changes.map { it.second.height })
    }

    @Test
    fun `an always block is drawn whether or not the key is down`() = onEdt {
        val editor = editor()
        val block = ByBlockHintPresentation(editor, member, mode = ByHintMode.ALWAYS)
        hold(ctrlAlt)
        assertEquals(2 * editor.lineHeight, block.height)
    }

    @Test
    fun `the same text under another mode is another hint`() = onEdt {
        val editor = editor()
        val always = ByBlockHintPresentation(editor, member, mode = ByHintMode.ALWAYS)
        assertFalse(ByBlockHintPresentation(editor, member, mode = ByHintMode.ALWAYS).updateState(always))
        assertTrue(ByBlockHintPresentation(editor, member, mode = ByHintMode.ON_PUSH).updateState(always))
        assertTrue(ByBlockHintPresentation(editor, "\n    def h(self): ...").updateState(always))
    }

    // endregion

    // region: clicks

    private val intLocation = Location("file:///p/builtins.pyi", Range(Position(9, 6), Position(9, 9)))

    private val navigated = mutableListOf<Location>()
    private var accepted = 0

    /** `int` in the second line linked, as the collector builds it from the label's parts. */
    private fun linked(editor: Editor): ByBlockHintPresentation {
        val start = member.indexOf("int")
        return ByBlockHintPresentation(
            editor, member,
            links = listOf(ByHintLink(start, start + 3, intLocation)),
            navigate = { navigated += it },
            accept = { accepted++ },
        )
    }

    private val navigateModifier = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    private fun click(block: ByBlockHintPresentation, editor: Editor, point: Point, modifiers: Int = 0, count: Int = 1) {
        val source = editor.contentComponent
        val press = MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, modifiers, point.x, point.y, count, false, MouseEvent.BUTTON1)
        block.mousePressed(press, point)
        if (press.isConsumed) return
        block.mouseClicked(MouseEvent(source, MouseEvent.MOUSE_CLICKED, 0L, modifiers, point.x, point.y, count, false, MouseEvent.BUTTON1), point)
    }

    /** The middle of `int` on the label's second line. */
    private fun intPoint(editor: Editor): Point {
        val column = "    def g(self, a: ".length
        return Point((editor.columnX(column) + editor.columnX(column + 3)) / 2, editor.lineHeight + editor.lineHeight / 2)
    }

    @Test
    fun `a Ctrl+click on a named part of any line goes where it names`() = onEdt {
        val editor = editor()
        val block = linked(editor)
        click(block, editor, intPoint(editor), navigateModifier)
        assertEquals(listOf(intLocation), navigated)
    }

    @Test
    fun `the same place on another line, or a plain click, goes nowhere`() = onEdt {
        val editor = editor()
        val block = linked(editor)
        val point = intPoint(editor)
        click(block, editor, Point(point.x, editor.lineHeight / 2), navigateModifier)
        click(block, editor, point)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a double click writes the member in`() = onEdt {
        val editor = editor()
        val block = linked(editor)
        click(block, editor, intPoint(editor), count = 2)
        assertEquals(1, accepted)
    }

    // endregion
}
