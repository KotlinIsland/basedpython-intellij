package dev.basedpython.pycharm.navigation

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Next/Previous Method, Code Block Start/End and method separators in a real editor, with `by`'s
 * outline put in by hand — each as PyCharm moves and draws in the same program as `.py` (measured
 * in 263.7528).
 */
@TestFixtures
class ByOutlineNavigationTest {

    private val fixture by codeInsightFixture()

    private val text = """
        |import math
        |
        |
        |def logged(func):
        |    def wrapper(*args):
        |        return func(*args)
        |    return wrapper
        |
        |
        |class Shape:
        |    def __init__(self, side):
        |        self.side = side
        |
        |    @logged
        |    def area(self):
        |        try:
        |            value = self.side
        |        except ValueError:
        |            value = 0
        |        return value
        |
        |
        |def total(shapes):
        |    return 0
        |""".trimMargin()

    private fun OutlineSpec.Suite.program() {
        simple("import math")
        def("def logged(func):") {
            def("def wrapper(*args):") { simple("return func(*args)") }
            simple("return wrapper")
        }
        compound(name = "Shape") {
            clause("class Shape:") {
                def("def __init__(self, side):") { simple("self.side = side") }
                compound(decorators = "@logged", name = "area") {
                    clause("def area(self):") {
                        compound {
                            clause("try:") { simple("value = self.side") }
                            clause("except ValueError:") { simple("value = 0") }
                        }
                        simple("return value")
                    }
                }
            }
        }
        def("def total(shapes):") { simple("return 0") }
    }

    private fun open(caretAt: String) {
        fixture.configureByText("a.by", text.replaceFirst(caretAt, "<caret>$caretAt"))
        OutlineSpec.remember(fixture.project, fixture.editor.document) { program() }
    }

    /** Where the caret is after each of [times] presses of [action], as `line:column`, both from 1. */
    private fun presses(action: String, times: Int): List<String> = List(times) {
        fixture.performEditorAction(action)
        val document = fixture.editor.document
        val offset = fixture.editor.caretModel.offset
        val line = document.getLineNumber(offset)
        "${line + 1}:${offset - document.getLineStartOffset(line) + 1}"
    }

    /**
     * The places Next and Previous Method step between. The actions themselves are enabled only
     * where a structure view is, which in an IDE is the platform's LSP one and in a test is none.
     */
    @Test
    fun `Next and Previous Method step through module functions and methods, not nested functions or classes`() = onEdt {
        open("import math")
        val offsets = ByMethodNavigation().getMethodNavigationOffsets(fixture.file, 0)!!
        val document = fixture.editor.document
        assertEquals(
            listOf("4:5", "11:9", "15:9", "23:5"),
            offsets.map { val line = document.getLineNumber(it); "${line + 1}:${it - document.getLineStartOffset(line) + 1}" },
        )
    }

    @Test
    fun `Code Block Start goes to the statement around the caret, then the one around that`() = onEdt {
        open("value = 0")
        assertEquals(listOf("16:9", "14:5", "10:1"), presses("EditorCodeBlockStart", 3))
    }

    @Test
    fun `Code Block End goes to its end, then the end of the one around that`() = onEdt {
        open("value = self.side")
        // the class ends where its last method does, so there is nothing further out to go to
        assertEquals(listOf("19:22", "20:21", "20:21"), presses("EditorCodeBlockEnd", 3))
    }

    @Test
    fun `a separator goes above each definition that follows another in its suite`() = onEdt {
        val settings = DaemonCodeAnalyzerSettings.getInstance()
        val was = settings.SHOW_METHOD_SEPARATORS
        settings.SHOW_METHOD_SEPARATORS = true
        try {
            open("import math")
            fixture.doHighlighting()
            val lines = fixture.editor.markupModel.allHighlighters
                .plus(com.intellij.openapi.editor.impl.DocumentMarkupModel.forDocument(fixture.editor.document, fixture.project, true).allHighlighters)
                .filter { it.lineSeparatorColor != null }
                .map { fixture.editor.document.getLineNumber(it.startOffset) + 1 }
                .sorted()
            assertEquals(listOf(10, 14, 23), lines)
        } finally {
            settings.SHOW_METHOD_SEPARATORS = was
        }
    }

    @Test
    fun `without an outline there is nowhere to go`() = onEdt {
        fixture.configureByText("a.by", text)
        assertEquals(null, ByMethodNavigation().getMethodNavigationOffsets(fixture.file, 0))
        assertEquals(listOf("1:1"), presses("EditorCodeBlockEnd", 1))
    }
}
