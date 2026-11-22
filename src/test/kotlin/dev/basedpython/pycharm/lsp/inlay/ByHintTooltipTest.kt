package dev.basedpython.pycharm.lsp.inlay

import com.intellij.openapi.application.UI
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByHover
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.MouseEvent
import kotlin.time.Duration.Companion.milliseconds

/** Which tooltip hovering a hint shows at each place in it, and that it is HTML by then. */
@TestFixtures
class ByHintTooltipTest {

    private val fixture by codeInsightFixture()

    private val place = Location("file:///p/a.by", Range(Position(1, 0), Position(1, 4)))

    /** What the tooltips put on the screen, in order. */
    private val shown = mutableListOf<String>()

    private fun tooltip(
        hintTip: ByTip? = null,
        fallback: String? = null,
        spans: List<ByHintSpan> = emptyList(),
        docs: Boolean = true,
        answer: (Location) -> ByHover = { ByHover.Nothing },
    ): ByHintTooltip {
        lateinit var tooltip: ByHintTooltip
        onEdt {
            fixture.configureByText("a.txt", "x = 1")
            tooltip = ByHintTooltip(fixture.editor, fixture.project, hintTip, fallback, spans, docs, answer) { _, html ->
                shown += html
                null
            }
        }
        return tooltip
    }

    /**
     * Moves the mouse over [offset] as the editor passes a move on, on the EDT with no lock held, and
     * waits for [count] tooltips to have been shown since.
     */
    private fun ByHintTooltip.over(offset: Int?, count: Int = 1): List<String> = timeoutRunBlocking(context = Dispatchers.UI) {
        shown.clear()
        moved(MouseEvent(fixture.editor.contentComponent, MouseEvent.MOUSE_MOVED, 0, 0, 1, 1, 0, false), offset)
        while (shown.size < count) delay(10.milliseconds)
        shown.toList()
    }

    private fun ByHintTooltip.off() = timeoutRunBlocking(context = Dispatchers.UI) { exited() }

    @Test
    fun `plain text is escaped, so a type with angle brackets in it survives`() {
        assertEquals(listOf("list[&lt;locals&gt;.A]<br>more"), tooltip(fallback = "list[<locals>.A]\nmore").over(0))
    }

    @Test
    fun `a markdown tooltip is rendered, its code highlighted, off the event thread, which holds no lock as the mouse moves`() {
        val tooltip = tooltip(hintTip = ByTip("Overrides `Animal.speak`\n```by\ndef speak(self) -> str\n```", markdown = true))
        val html = tooltip.over(0).single()
        assertTrue("<code" in html && "Animal.speak" in html && "speak" in html.substringAfter("Animal.speak"), html)
        // converted once: hovering again shows it as the mouse gets there
        tooltip.off()
        timeoutRunBlocking(context = Dispatchers.UI) {
            shown.clear()
            tooltip.moved(MouseEvent(fixture.editor.contentComponent, MouseEvent.MOUSE_MOVED, 0, 0, 1, 1, 0, false), 0)
            assertEquals(listOf(html), shown)
        }
    }

    @Test
    fun `a tooltip still being converted when the mouse leaves is never shown`() {
        val tooltip = tooltip(hintTip = ByTip("Overrides `Animal.speak`", markdown = true), spans = listOf(ByHintSpan(2, 6, null, ByTip("part", markdown = false), null)))
        timeoutRunBlocking(context = Dispatchers.UI) {
            val move = MouseEvent(fixture.editor.contentComponent, MouseEvent.MOUSE_MOVED, 0, 0, 1, 1, 0, false)
            tooltip.moved(move, 0)
            tooltip.moved(move, 3)
            delay(300.milliseconds)
            assertEquals(listOf("part"), shown)
            tooltip.exited()
        }
    }

    @Test
    fun `a part's own tooltip wins over the hint's, the hint's shows elsewhere`() {
        val tooltip = tooltip(
            hintTip = ByTip("whole", markdown = false),
            spans = listOf(ByHintSpan(2, 6, null, ByTip("part", markdown = false), null)),
        )
        assertEquals(listOf("part"), tooltip.over(3))
        assertEquals(listOf("whole"), tooltip.over(0))
        tooltip.off()
        assertEquals(listOf("whole"), tooltip.over(null))
    }

    @Test
    fun `over a part that names a place, the hint's tooltip shows at once and by's docs go under it`() {
        // all of `override` names the member it overrides, so the hint's tooltip has to show over it
        val spans = listOf(ByHintSpan(0, 8, place, null, null))
        val tooltip = tooltip(hintTip = ByTip("Overrides `Animal.speak`", markdown = true), spans = spans) { ByHover.Markup("the docs") }
        val (first, after) = tooltip.over(3, count = 2)
        assertTrue("Animal.speak" in first && "the docs" !in first, first)
        assertTrue(after.startsWith("$first<hr>") && "the docs" in after, after)
        // the answer is kept: hovering again shows both at once
        tooltip.off()
        assertEquals(listOf(after), tooltip.over(3))
    }

    @Test
    fun `with no tooltip of the hint's own, by's docs are the whole of it`() {
        val tooltip = tooltip(spans = listOf(ByHintSpan(0, 8, place, null, null))) { ByHover.Markup("the docs") }
        val html = tooltip.over(3).single()
        assertTrue("the docs" in html && "<hr>" !in html, html)
    }

    @Test
    fun `where by has nothing to say about the place, the hint's tooltip stays`() {
        val asked = CompletableDeferred<Unit>()
        val tooltip = tooltip(hintTip = ByTip("Overrides `Animal.speak`", markdown = true), spans = listOf(ByHintSpan(0, 8, place, null, null))) {
            asked.complete(Unit)
            ByHover.Nothing
        }
        val first = tooltip.over(3).single()
        timeoutRunBlocking(context = Dispatchers.UI) {
            asked.await()
            delay(100.milliseconds)
            assertEquals(listOf(first), shown)
        }
        tooltip.off()
        assertEquals(listOf(first), tooltip.over(3))
    }

    @Test
    fun `a hint with nothing to say has no tooltip`() {
        assertTrue(tooltip().isEmpty)
        assertTrue(tooltip(spans = listOf(ByHintSpan(0, 1, place, null, null)), docs = false).isEmpty)
    }
}
