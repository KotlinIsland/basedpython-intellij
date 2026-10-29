package dev.basedpython.pycharm.editor.scope

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.highlighting.BackgroundHighlighter
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.DefaultLineMarkerRenderer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * *Current scope* in a `.by` editor: the block the caret is in, as `by`'s outline has it, marked as
 * the platform marks a braced language's — and the platform marking nothing of its own there.
 */
@TestFixtures
class ByCurrentScopeTest {

    private val fixture by codeInsightFixture()

    private val source = """
        |class A:
        |    def f(self):
        |
        |        if coin():
        |            for x in [1, 2]:
        |                print(1)
        |
        |                print(1)
        |            print(1)
        |        elif x:
        |            pass
        |        for i in x: pass
        |        match x:
        |            case 1:
        |                pass
        |            case _:
        |                pass
        |""".trimMargin()

    private val outline: OutlineSpec.Suite.() -> Unit = {
        compound(name = "A") {
            clause("class A:") {
                def("def f(self):", listOf("self")) {
                    compound {
                        clause("if coin():") {
                            compound {
                                clause("for x in [1, 2]:") {
                                    call("print(1)")
                                    call("print(1)")
                                }
                            }
                            call("print(1)")
                        }
                        clause("elif x:") { simple("pass") }
                    }
                    compound { clause("for i in x:") { simple("pass") } }
                    compound {
                        clause("match x:")
                        clause("case 1:") { simple("pass") }
                        clause("case _:") { simple("pass") }
                    }
                }
            }
        }
    }

    private fun linesAt(line: Int, column: Int): IntRange? {
        val document = fixture.editor.document
        return ByScopes.linesAt(
            OutlineSpec.outline(document, outline),
            document.immutableCharSequence,
            document.getLineStartOffset(line) + column,
        )
    }

    @Test
    fun `the block is chosen as Kotlin chooses a braced one`() = onEdt {
        fixture.configureByText("a.by", source)
        assertEquals(4..7, linesAt(5, 20), "in the loop's body: the loop, from its header to its last line")
        assertEquals(4..7, linesAt(6, 0), "a blank line of the body is in it")
        assertEquals(4..7, linesAt(4, 28), "just after the header's colon, as just after a {")
        assertEquals(3..8, linesAt(4, 18), "inside the header, as inside a for's parentheses: the block around it")
        assertEquals(9..10, linesAt(10, 14), "an elif is a block of its own")
        assertEquals(12..16, linesAt(13, 12), "a match is one block over its cases")
        assertEquals(13..14, linesAt(14, 18), "and each case one inside it")
        assertNull(linesAt(11, 22), "a block on one line has no lines to mark, as a { … } on one line has none")
    }

    @Test
    fun `the caret's block is marked as the platform marks a braced one, while Current scope is on`() = onEdt {
        fixture.configureByText("b.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
        val scope = ByCurrentScope.getInstance(fixture.project)
        CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE = true
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.getLineStartOffset(5) + 18)

        val mark = scope.markIn(fixture.editor)
        assertNotNull(mark)
        mark!!
        assertEquals(4..7, fixture.editor.document.getLineNumber(mark.startOffset)..fixture.editor.document.getLineNumber(mark.endOffset))
        assertEquals(0, mark.layer)
        assertEquals(HighlighterTargetArea.LINES_IN_RANGE, mark.targetArea)
        val renderer = (mark as RangeHighlighterEx).lineMarkerRenderer as DefaultLineMarkerRenderer
        assertEquals(LineMarkerRendererEx.Position.RIGHT, renderer.position)

        CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE = false
        fixture.editor.caretModel.moveToOffset(fixture.editor.document.getLineStartOffset(6))
        assertNull(scope.markIn(fixture.editor), "off: no mark")
    }

    /**
     * The platform finds a scope only from structural brace tokens, and a `.by` block has none, so it
     * marks nothing there — which is why this plugin does. If the platform starts finding one itself
     * (a code block fallback, draft 25-8731764), this fails, and the plugin's mark would be a second
     * one. The f-string field's braces, which are structural, show the platform's mark is seen here.
     */
    @Test
    fun `the platform marks no scope of its own for a by block, and does for a braced one`() = onEdt {
        val text = "def f():\n    for x in y:\n        print(1)\n        print(2)\ns = f\"{\n    x\n}\"\n"
        fixture.configureByText("c.by", text)
        CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE = true
        fun platformMarks(offset: Int): List<String> {
            var marks = emptyList<String>()
            BackgroundHighlighter.runWithEnabledListenersInTest(fixture.project) {
                fixture.editor.caretModel.moveToOffset(0)
                fixture.editor.caretModel.moveToOffset(offset)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                BackgroundHighlighter.waitForIdentifierHighlighting(fixture.editor)
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                val ours = ByCurrentScope.getInstance(fixture.project).markIn(fixture.editor)
                marks = fixture.editor.markupModel.allHighlighters
                    .filter { (it as RangeHighlighterEx).lineMarkerRenderer != null && it !== ours }
                    .map { "${fixture.editor.document.getLineNumber(it.startOffset)}-${fixture.editor.document.getLineNumber(it.endOffset)}" }
            }
            return marks
        }
        assertEquals(listOf("4-6"), platformMarks(text.indexOf("    x") + 4), "the f-string field: the platform's own mark")
        assertEquals(emptyList<String>(), platformMarks(text.indexOf("print(1)") + 3), "a by block: nothing of the platform's")
    }

    @AfterEach
    fun reset() {
        CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE = false
    }
}
