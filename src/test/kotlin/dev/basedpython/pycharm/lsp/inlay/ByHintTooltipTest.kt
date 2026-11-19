package dev.basedpython.pycharm.lsp.inlay

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which tooltip hovering a hint shows at each place in it, and that it is HTML by then. */
@TestFixtures
class ByHintTooltipTest {

    private val fixture by codeInsightFixture()

    private val place = Location("file:///p/a.by", Range(Position(1, 0), Position(1, 4)))

    private fun tooltip(hintTip: ByTip? = null, fallback: String? = null, spans: List<ByHintSpan> = emptyList(), docs: Boolean = true): ByHintTooltip {
        fixture.configureByText("a.txt", "x = 1")
        return ByHintTooltip(fixture.editor, fixture.project, hintTip, fallback, spans, docs)
    }

    @Test
    fun `plain text is escaped, so a type with angle brackets in it survives`() = onEdt {
        val html = tooltip(fallback = "list[<locals>.A]\nmore").immediateHtml(0)
        assertEquals("list[&lt;locals&gt;.A]<br>more", html)
    }

    @Test
    fun `a markdown tooltip is rendered`() = onEdt {
        val html = tooltip(hintTip = ByTip("Overrides `Animal.speak`", markdown = true)).immediateHtml(0).orEmpty()
        assertTrue("<code" in html && "Animal.speak" in html, html)
    }

    @Test
    fun `a part's own tooltip wins over the hint's, the hint's shows elsewhere`() = onEdt {
        val tooltip = tooltip(
            hintTip = ByTip("whole", markdown = false),
            spans = listOf(ByHintSpan(2, 6, null, ByTip("part", markdown = false), null)),
        )
        assertEquals("part", tooltip.immediateHtml(3))
        assertEquals("whole", tooltip.immediateHtml(0))
        assertEquals("whole", tooltip.immediateHtml(null))
    }

    @Test
    fun `over a part that names a place, the hint's tooltip shows at once and by's docs go under it`() = onEdt {
        // all of `override` names the member it overrides, so the hint's tooltip has to show over it
        val tooltip = tooltip(hintTip = ByTip("Overrides `Animal.speak`", markdown = true), spans = listOf(ByHintSpan(0, 8, place, null, null)))
        val first = tooltip.immediateHtml(3).orEmpty()
        assertTrue("Animal.speak" in first, first)
        val after = tooltip.withDocs("<p>def speak(self)</p>").orEmpty()
        assertTrue(after.startsWith(first) && after.endsWith("<hr><p>def speak(self)</p>"), after)
        // by had nothing to say: the hint's tooltip stays as it was
        assertEquals(first, tooltip.withDocs(""))
        // and with no tooltip of the hint's own, the docs are the whole of it
        assertEquals("<p>docs</p>", tooltip(spans = listOf(ByHintSpan(0, 8, place, null, null))).withDocs("<p>docs</p>"))
        assertNull(tooltip(spans = listOf(ByHintSpan(0, 8, place, null, null))).immediateHtml(3))
    }

    @Test
    fun `a hint with nothing to say has no tooltip`() = onEdt {
        assertTrue(tooltip().isEmpty)
        assertTrue(tooltip(spans = listOf(ByHintSpan(0, 1, place, null, null)), docs = false).isEmpty)
    }
}
