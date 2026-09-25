package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.PresentationListener
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.util.SystemInfo
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JPanel
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

/**
 * What a push-to-hint hint measures, that it says so when the key moves, and what a click on a hint
 * means.
 *
 * The size event is the whole mechanism: the platform listens on every presentation it renders and
 * turns one into `Inlay.update()`, which is what re-measures a width it has already cached. Tested
 * against a real editor, since the width is the editor's own font metrics.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByInlayHintPresentationTest {

    private val fixture by codeInsightFixture()

    private val push get() = ByHintPush.getInstance()

    private val source = JPanel()

    private fun editor(): Editor {
        fixture.configureByText("a.txt", "value = 1")
        return fixture.editor
    }

    private fun hint(mode: ByHintMode, editor: Editor = editor()) = ByInlayHintPresentation(
        editor = editor,
        text = ": int",
        padLeft = false,
        padRight = false,
        mode = mode,
        pushKey = ByPushKey.CTRL_ALT,
    )

    private fun hold(modifiersEx: Int) {
        push.onEvent(
            KeyEvent(source, KeyEvent.KEY_PRESSED, 0L, modifiersEx, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED),
        )
    }

    private val ctrlAlt = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK

    /** The push state is the application's, and so is shared with whatever ran before this. */
    @BeforeEach
    fun releaseEverything() {
        hold(0)
    }

    /** Records what a presentation tells its listeners, the way the platform's own listener does. */
    private class RecordingListener : PresentationListener {
        val sizes = mutableListOf<Pair<Dimension, Dimension>>()
        var repaints = 0
        override fun sizeChanged(previous: Dimension, current: Dimension) {
            sizes += previous to current
        }

        override fun contentChanged(area: Rectangle) {
            repaints++
        }
    }

    /**
     * A hint costs its text and no padding — which is checked as *drift*, because "the width of this
     * text as source" is not a single number.
     *
     * The editor floors each accumulated fractional position, so the same characters span a
     * different number of pixels depending on which column they start at (see `textWidth`). What can
     * be pinned down is that a hint never systematically overspends: lay the same annotation out as
     * a hint and as source and the two lines stay together, whatever the text.
     */
    @Test
    fun `no annotation drifts when a hint stands in for it`() {
        val annotations = listOf(": int", ": A[int]", ": dict[Key=str, Value=int]", "override ", "t=")
        for ((index, annotation) in annotations.withIndex()) {
            // A file each: the fixture hands back one editor per name, and inlays added to it would
            // otherwise still be there on the next pass round.
            fixture.configureByText("drift$index.by", "a = 1\na$annotation = 1")
            val editor = fixture.editor
            hintAt(editor, offset = 1, text = annotation)

            val document = editor.document
            val inferred = editor.offsetToXY(document.getLineEndOffset(0)).x
            val written = editor.offsetToXY(document.getLineEndOffset(1)).x
            assertTrue(
                kotlin.math.abs(inferred - written) <= 1,
                "\"$annotation\" drifts ${inferred - written}px as a hint; a hint may cost its text " +
                    "and nothing else, and the nearest whole pixel to that is the most it may miss by",
            )
        }
    }

    /**
     * The same annotation, written out on one line and left to the hints on the other, has to leave
     * both lines identical to the pixel.
     *
     * ```
     * class A[T]:
     *     init(let t: T)
     *
     * a = A(1)          ->  a: A[int] = A[int](t=1)
     * a: A[int] = A(1)  ->  a: A[int] = A[int](t=1)
     * ```
     *
     * Three hints on the first line, two on the second, so anything a hint spends beyond its own
     * text shows up here as drift and nowhere else — which is exactly how it was found. Driven
     * through real inlays and the editor's own layout, since the claim is about where the editor puts
     * things, not about what this class returns.
     */
    @Test
    fun `a hinted line and the written-out line land in the same place`() {
        fixture.configureByText("a.by", "a = A(1)\na: A[int] = A(1)")
        val editor = fixture.editor

        // `a‸ = A‸(‸1)` and `a: A[int] = A‸(‸1)`, the hints `by` sends for the two forms.
        hintAt(editor, offset = 1, text = ": A[int]")
        hintAt(editor, offset = 5, text = "[int]")
        hintAt(editor, offset = 6, text = "t=")
        hintAt(editor, offset = 22, text = "[int]")
        hintAt(editor, offset = 23, text = "t=")

        val document = editor.document
        val inferred = editor.offsetToXY(document.getLineEndOffset(0)).x
        val written = editor.offsetToXY(document.getLineEndOffset(1)).x

        assertEquals(
            written,
            inferred,
            "the inferred line ends ${inferred - written}px from the written-out one; a hint may " +
                "cost its text and nothing else, or lines carrying different numbers of them drift",
        )
    }

    private fun hintAt(editor: Editor, offset: Int, text: String) {
        val presentation = ByInlayHintPresentation(
            editor = editor,
            text = text,
            padLeft = false,
            padRight = false,
        )
        editor.inlayModel.addInlineElement(offset, false, PresentationRenderer(presentation))
    }

    @Test
    fun `an always hint measures its text whether or not the key is down`() {
        val presentation = hint(ByHintMode.ALWAYS)
        val width = presentation.width
        assertTrue(width > 1, "expected the text's own width, got $width")

        hold(ctrlAlt)
        assertEquals(width, presentation.width)
    }

    @Test
    fun `a push hint takes no room until the key goes down, and gives it back after`() {
        val presentation = hint(ByHintMode.ON_PUSH)
        assertEquals(1, presentation.width, "a hidden hint is the narrowest inlay the editor allows")

        hold(ctrlAlt)
        assertTrue(presentation.width > 1, "the hint should measure its text while the key is held")

        hold(0)
        assertEquals(1, presentation.width)
    }

    @Test
    fun `the key moving is reported as a resize, which is what re-measures the inlay`() {
        val presentation = hint(ByHintMode.ON_PUSH)
        val listener = RecordingListener()
        presentation.addListener(listener)

        hold(ctrlAlt)
        assertEquals(1, listener.sizes.size)
        val (before, after) = listener.sizes.single()
        assertEquals(1, before.width)
        assertTrue(after.width > 1)
        assertEquals(1, listener.repaints)

        hold(0)
        assertEquals(2, listener.sizes.size)
        assertEquals(1, listener.sizes.last().second.width)
    }

    @Test
    fun `a hint held under a key that is not its own stays hidden`() {
        val presentation = hint(ByHintMode.ON_PUSH)
        hold(InputEvent.SHIFT_DOWN_MASK)
        assertEquals(1, presentation.width)
    }

    @Test
    fun `a hint built while the key is already down starts out visible`() {
        // An editor opened mid-push, or a daemon pass that ran during one.
        hold(ctrlAlt)
        assertTrue(hint(ByHintMode.ON_PUSH).width > 1)
    }

    // region: clicks

    private val listLocation = Location("file:///p/builtins.byi", Range(Position(1, 6), Position(1, 10)))

    private val navigated = mutableListOf<Location>()
    private var accepted = 0

    /** `: list[int]` with `list` linked, as the collector builds it. */
    private fun linkedHint(accept: Boolean = true) = ByInlayHintPresentation(
        editor = editor(),
        text = ": list[int]",
        padLeft = false,
        padRight = false,
        links = listOf(ByHintLink(2, 6, listLocation)),
        navigate = { navigated += it },
        accept = if (accept) ({ accepted++ }) else null,
    )

    /** The x a click lands on to hit the middle of characters [from] until [to] of [text]. */
    private fun ByInlayHintPresentation.xOf(from: Int, to: Int): Int {
        val font = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        val metrics = editor.contentComponent.getFontMetrics(font)
        fun advance(end: Int) = font.getStringBounds(text, 0, end, metrics.fontRenderContext).width
        return ((advance(from) + advance(to)) / 2).toInt()
    }

    private val navigateModifier = if (SystemInfo.isMac) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK

    /** A press and the click after it, which is how the editor hands a presentation a gesture's last click. */
    private fun click(presentation: ByInlayHintPresentation, x: Int, modifiers: Int = 0, button: Int = MouseEvent.BUTTON1, count: Int = 1) {
        val source = presentation.editor.contentComponent
        val press = MouseEvent(source, MouseEvent.MOUSE_PRESSED, 0L, modifiers, x, 5, count, false, button)
        presentation.mousePressed(press, Point(x, 5))
        // the editor sends no click for a press something consumed
        if (press.isConsumed) return
        presentation.mouseClicked(MouseEvent(source, MouseEvent.MOUSE_CLICKED, 0L, modifiers, x, 5, count, false, button), Point(x, 5))
    }

    @Test
    fun `a Ctrl+click on a named part goes where it names`() {
        val hint = linkedHint()
        click(hint, hint.xOf(2, 6), navigateModifier)
        assertEquals(listOf(listLocation), navigated)
    }

    @Test
    fun `a middle click on a named part goes there too`() {
        val hint = linkedHint()
        click(hint, hint.xOf(2, 6), button = MouseEvent.BUTTON2)
        assertEquals(listOf(listLocation), navigated)
    }

    @Test
    fun `a plain click goes nowhere, as a click on code does not`() {
        val hint = linkedHint()
        click(hint, hint.xOf(2, 6))
        assertTrue(navigated.isEmpty())
        assertEquals(0, accepted)
    }

    @Test
    fun `a Ctrl+click on punctuation goes nowhere`() {
        val hint = linkedHint()
        click(hint, hint.xOf(6, 7), navigateModifier)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `a double click writes the hint in`() {
        val hint = linkedHint()
        click(hint, hint.xOf(0, 1), count = 2)
        assertEquals(1, accepted)
        assertTrue(navigated.isEmpty())
    }

    @Test
    fun `the press that makes it a double click is kept from the editor, which would select a word`() {
        fun press(hint: ByInlayHintPresentation, count: Int): MouseEvent {
            val event = MouseEvent(hint.editor.contentComponent, MouseEvent.MOUSE_PRESSED, 0L, InputEvent.BUTTON1_DOWN_MASK, 1, 5, count, false, MouseEvent.BUTTON1)
            hint.mousePressed(event, Point(1, 5))
            return event
        }
        assertFalse(press(linkedHint(), 1).isConsumed)
        assertTrue(press(linkedHint(), 2).isConsumed)
        // nothing to write in, so a double click on it is the editor's as on any other text
        assertFalse(press(linkedHint(accept = false), 2).isConsumed)
    }

    @Test
    fun `a hint by sent no edit for ignores a double click`() {
        val hint = linkedHint(accept = false)
        click(hint, hint.xOf(0, 1), count = 2)
        assertEquals(0, accepted)
    }

    @Test
    fun `a push hint that is not drawn cannot be clicked`() {
        val hint = ByInlayHintPresentation(
            editor = editor(),
            text = ": list[int]",
            padLeft = false,
            padRight = false,
            mode = ByHintMode.ON_PUSH,
            links = listOf(ByHintLink(2, 6, listLocation)),
            navigate = { navigated += it },
            accept = { accepted++ },
        )
        click(hint, hint.xOf(2, 6), navigateModifier)
        click(hint, hint.xOf(0, 1), count = 2)
        assertTrue(navigated.isEmpty())
        assertEquals(0, accepted)
    }

    // endregion
}
