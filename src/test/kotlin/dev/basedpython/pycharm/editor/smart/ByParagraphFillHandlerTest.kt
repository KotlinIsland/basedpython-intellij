package dev.basedpython.pycharm.editor.smart

import com.intellij.application.options.CodeStyle
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Edit | Fill Paragraph over a `.by` comment, through the real action. */
@TestFixtures
class ByParagraphFillHandlerTest {

    private val fixture by codeInsightFixture()

    /**
     * The lines the wrap makes go on as comments in the IDE (measured in 263.7528); in the test
     * fixture the platform's wrap leaves them bare, so what is checked here is the fill itself — the
     * run joined and re-wrapped at the margin, every word kept, in order.
     */
    @Test
    fun `a run of comments is joined and re-wrapped at the right margin`() = onEdt {
        val words = (1..40).joinToString(" ") { "word$it" }
        fixture.configureByText("a.by", "def f():\n    # ${words.substringBefore(" word20")}\n    # <caret>word20${words.substringAfter(" word20")}\n    pass\n")
        fixture.performEditorAction("FillParagraph")
        val margin = CodeStyle.getSettings(fixture.file).getRightMargin(BasedPythonLanguage)
        val lines = fixture.editor.document.text.lines().drop(1).takeWhile { it.trim() != "pass" }
        assertTrue(lines.size > 1 && lines.first().startsWith("    # word1 ")) { fixture.editor.document.text }
        assertTrue(lines.all { it.trimEnd().length <= margin }) { fixture.editor.document.text }
        assertEquals(words, lines.joinToString(" ") { it.trim().removePrefix("#").trim() })
    }
}
