package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.event.EditorEventMulticasterImpl
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.replaceService
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the margins `by` reports reach an editor, and are placed where the stripped indentation
 * ends.
 *
 * Everything between the server's answer and the pixels is registration, reconciliation and layout
 * — the `highlightingPassFactory` entry in plugin.xml, the diff [ByStringMarginPassFactory] does
 * against what is already drawn, and [StringMargins.marginOf] placing the rule on the literal's
 * lines — and none of it needs a server to be wrong.
 */
@TestFixtures
class ByStringMarginPassTest {

    private val fixture by codeInsightFixture()

    private val q = "\"\"\""

    /** The margin highlighters, in document order. */
    private fun marginHighlighters() = fixture.editor.markupModel.allHighlighters
        .filter { (it as? RangeHighlighterEx)?.customRenderer is ByStringMarginRenderer }
        .sortedBy { it.startOffset }

    /**
     * The margins currently drawn in the fixture's editor, measured the way the renderer measures
     * them: from each marked literal's *live* range, and the indent its renderer carries.
     */
    private fun drawn(): List<StringMargin> {
        val text = fixture.editor.document.immutableCharSequence
        return marginHighlighters().mapNotNull {
            val indent = ((it as RangeHighlighterEx).customRenderer as ByStringMarginRenderer).indent
            StringMargins.marginOf(text, it.startOffset, it.endOffset, indent)
        }
    }

    @Test
    fun `the pass draws a margin for each literal by strips`() = onEdt {
        fixture.configureByText("a.by", "a = $q\n    one\n    $q\nb = $q\n      two\n  $q\nc = \"three\"\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("$q\n    one\n    $q", strippedIndent = 4)
            string("$q\n      two\n  $q", strippedIndent = 6)
            string("\"three\"")
        }
        fixture.doHighlighting()
        assertEquals(listOf(4, 6), drawn().map { it.indent })
    }

    /**
     * A docstring whose text starts on the opening line is left as written by the transpiler, and
     * so gets no margin — the scanner this replaced drew one there, at the indentation of the lines
     * below, marking a strip that never happens.
     */
    @Test
    fun `no highlighter where by strips nothing`() = onEdt {
        fixture.configureByText("b.by", "a = ${q}Summary.\n    more\n    $q\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("${q}Summary.\n    more\n    $q")
        }
        fixture.doHighlighting()
        assertEquals(emptyList<StringMargin>(), drawn())
    }

    @Test
    fun `no answer from by is no margin`() = onEdt {
        fixture.configureByText("c.by", "a = $q\n    one\n    $q\n")
        fixture.doHighlighting()
        assertEquals(emptyList<StringMargin>(), drawn())
    }

    /**
     * The rule is drawn at the column `by` strips to — the content's, here eight — which is not the
     * column of the closing quotes, four in. The transpiler strips what the content lines share and
     * leaves the closing line out of it.
     */
    @Test
    fun `the margin lands where the content is stripped to, not on the closing quotes`() = onEdt {
        fixture.configureByText("d.by", "a = $q\n        one\n\n        two\n    $q\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("$q\n        one\n\n        two\n    $q", strippedIndent = 8)
        }
        fixture.doHighlighting()
        val editor = fixture.editor
        val text = editor.document.text

        val margin = drawn().single()
        assertEquals(editor.offsetToXY(text.indexOf("one")).x, editor.offsetToXY(margin.anchorOffset).x)
        assertEquals(text.indexOf("        one"), margin.firstLineStart)
        assertEquals(text.indexOf("        two"), margin.lastLineStart)
    }

    @Test
    fun `a tab-indented literal is anchored after its tabs`() = onEdt {
        val text = "a = $q\n\t\tone\n\t$q\n"
        val margin = StringMargins.marginOf(text, text.indexOf(q), text.lastIndexOf(q) + 3, 2)!!
        assertEquals(text.indexOf("one"), margin.anchorOffset)
    }

    /** Between an edit and the next answer the literal can lose its shape; nothing is drawn then. */
    @Test
    fun `a literal whose shape no longer fits its indent is not drawn`() = onEdt {
        val text = "a = $q\n  one\n    $q\n"
        assertNull(StringMargins.marginOf(text, text.indexOf(q), text.lastIndexOf(q) + 3, 4))
    }

    /**
     * The rule shares its column with the editor's own indent guide, and must share its colour.
     *
     * A multiline string's lines are an indented run like any other, so the platform draws a
     * guide down it at the indentation they share — the trim column, by the same arithmetic. It
     * draws that guide only on the run's *interior* lines, and over the top of ours. Two colours
     * at one column is therefore not two lines a reader can tell apart: it is one line that
     * changes colour partway down, which is what a string-coloured margin actually looked like.
     */
    @Test
    fun `the rule is drawn in the colour of the editor's own indent guide`() = onEdt {
        fixture.configureByText("e.by", "a = $q\n    one\n    $q\n")
        val scheme = fixture.editor.colorsScheme
        assertEquals(
            scheme.getColor(EditorColors.INDENT_GUIDE_COLOR),
            ByStringMarginColors.color(scheme),
        )
    }

    /**
     * The pass runs on every keystroke, so a margin that has not moved must not be replaced —
     * a new highlighter repaints the literal, and there is one of these per string in the file.
     */
    @Test
    fun `a second pass over unchanged text reuses the highlighters`() = onEdt {
        fixture.configureByText("f.by", "a = $q\n    one\n    $q\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("$q\n    one\n    $q", strippedIndent = 4)
        }
        fixture.doHighlighting()
        val first = marginHighlighters()
        assertEquals(1, first.size, "the fixture needs one margin for this to be testing anything")

        fixture.doHighlighting()
        // Identity, which is the claim: the same objects, not equal ones.
        assertTrue(first.zip(marginHighlighters()).all { (a, b) -> a === b })
    }

    /**
     * What unloading the plugin does to an editor that is still open: the service that owns the
     * margins is disposed, and nothing of this plugin's may be left behind in the editor — no
     * highlighter carrying the renderer, no listener. Either would keep the plugin's classloader
     * alive for as long as the editor stays open.
     */
    @Test
    fun `disposing the margins takes everything back out of an open editor`() = onEdt {
        val owner = Disposer.newDisposable("margins under test")
        try {
            val margins = ByStringMarginEditors()
            fixture.project.replaceService(ByStringMarginEditors::class.java, margins, owner)
            // Listeners of this class, from any instance: the light project outlives a test, and the
            // service an earlier test created is still registered alongside this one.
            val multicaster = EditorFactory.getInstance().eventMulticaster as EditorEventMulticasterImpl
            fun listeners() = multicaster.listeners.values.flatten()
                .count { it.javaClass.name.startsWith(ByStringMarginEditors::class.java.name) }

            fixture.configureByText("g.by", "a = $q\n    one\n    $q\n")
            OutlineSpec.remember(fixture.project, fixture.editor.document) {
                string("$q\n    one\n    $q", strippedIndent = 4)
            }
            fixture.doHighlighting()
            assertEquals(1, marginHighlighters().size, "the fixture needs a margin for this to be testing anything")
            val registered = listeners()

            Disposer.dispose(margins)

            assertEquals(emptyList<RangeHighlighter>(), marginHighlighters())
            assertEquals(registered - 1, listeners(), "the disposed service's repaint listener is gone")
        } finally {
            Disposer.dispose(owner)
        }
    }
}
