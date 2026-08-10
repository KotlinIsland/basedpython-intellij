package dev.basedpython.pycharm.refactoring

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * End-to-end tests that drive the document mutation produced by the extraction plans through a
 * real editor + document, mirroring exactly what the actions do inside their write command.
 *
 * We don't invoke the [AbstractExtractionAction] subclasses directly because they pop a modal
 * name-input dialog; instead we replicate the (tiny, deterministic) apply step here using the
 * pure [ExtractionLogic] plan, which is the only document-mutating code path the actions run.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ExtractionActionEndToEndTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private fun applyExtractVariable(name: String) = applyPlan { text, s, e ->
        ExtractionLogic.planExtractVariable(text, s, e, name)
    }

    private fun applyIntroduceConstant(name: String) = applyPlan { text, s, e ->
        ExtractionLogic.planIntroduceConstant(text, s, e, name)
    }

    private fun applyPlan(plan: (String, Int, Int) -> ExtractionLogic.ExtractionPlan) {
        val editor: Editor = fixture.editor
        val sel = editor.selectionModel
        val text = editor.document.charsSequence.toString()
        val p = plan(text, sel.selectionStart, sel.selectionEnd)
        WriteCommandAction.runWriteCommandAction(project) {
            if (p.insertOffset <= p.replaceStart) {
                editor.document.replaceString(p.replaceStart, p.replaceEnd, p.replaceWith)
                editor.document.insertString(p.insertOffset, p.insertText)
            } else {
                editor.document.insertString(p.insertOffset, p.insertText)
                editor.document.replaceString(p.replaceStart, p.replaceEnd, p.replaceWith)
            }
        }
    }

    // ------------------------------------------------------------------
    // Extract Variable
    // ------------------------------------------------------------------

    @Test
    fun `extract variable at top level`() {
        fixture.configureByText("a.by", "result = <selection>1 + 2</selection>\n")
        applyExtractVariable("tmp")
        fixture.checkResult("tmp = 1 + 2\nresult = tmp\n")
    }

    @Test
    fun `extract variable inside function body`() {
        fixture.configureByText(
            "a.by",
            "def f():\n    return <selection>a + b</selection>\n",
        )
        applyExtractVariable("s")
        fixture.checkResult("def f():\n    s = a + b\n    return s\n")
    }

    @Test
    fun `extract variable deeply nested`() {
        fixture.configureByText(
            "a.by",
            "def f():\n    if cond:\n        x = <selection>foo()</selection>\n",
        )
        applyExtractVariable("v")
        fixture.checkResult("def f():\n    if cond:\n        v = foo()\n        x = v\n")
    }

    @Test
    fun `extract variable preserves tab indentation`() {
        fixture.configureByText("a.by", "def f():\n\treturn <selection>a + b</selection>\n")
        applyExtractVariable("t")
        fixture.checkResult("def f():\n\tt = a + b\n\treturn t\n")
    }

    @Test
    fun `extract variable no trailing newline`() {
        fixture.configureByText("a.by", "result = <selection>x * y</selection>")
        applyExtractVariable("p")
        fixture.checkResult("p = x * y\nresult = p")
    }

    @Test
    fun `extract variable partial expression in middle`() {
        fixture.configureByText("a.by", "z = a + <selection>b * c</selection> + d\n")
        applyExtractVariable("m")
        fixture.checkResult("m = b * c\nz = a + m + d\n")
    }

    @Test
    fun `extract variable whole rhs`() {
        fixture.configureByText("a.by", "total = <selection>price + tax</selection>\n")
        applyExtractVariable("subtotal")
        fixture.checkResult("subtotal = price + tax\ntotal = subtotal\n")
    }

    // ------------------------------------------------------------------
    // Introduce Constant
    // ------------------------------------------------------------------

    @Test
    fun `introduce constant at top of plain file`() {
        fixture.configureByText("a.by", "x = <selection>42</selection>\n")
        applyIntroduceConstant("ANSWER")
        fixture.checkResult("ANSWER = 42\n\nx = ANSWER\n")
    }

    @Test
    fun `introduce constant after single import`() {
        fixture.configureByText("a.by", "import os\nx = <selection>magic</selection>\n")
        applyIntroduceConstant("MAGIC")
        fixture.checkResult("import os\nMAGIC = magic\nx = MAGIC\n")
    }

    @Test
    fun `introduce constant after multiple imports`() {
        fixture.configureByText(
            "a.by",
            "import os\nimport sys\nfrom a import b\ny = <selection>compute()</selection>\n",
        )
        applyIntroduceConstant("VALUE")
        fixture.checkResult(
            "import os\nimport sys\nfrom a import b\nVALUE = compute()\ny = VALUE\n",
        )
    }

    @Test
    fun `introduce constant after leading comments`() {
        fixture.configureByText(
            "a.by",
            "# header\n# coding: utf-8\nx = <selection>99</selection>\n",
        )
        applyIntroduceConstant("LIMIT")
        fixture.checkResult("# header\n# coding: utf-8\nLIMIT = 99\nx = LIMIT\n")
    }

    @Test
    fun `introduce constant after comments then imports`() {
        fixture.configureByText(
            "a.by",
            "# top\nimport os\nx = <selection>7</selection>\n",
        )
        applyIntroduceConstant("SEVEN")
        fixture.checkResult("# top\nimport os\nSEVEN = 7\nx = SEVEN\n")
    }

    @Test
    fun `introduce constant from selection on later line`() {
        fixture.configureByText(
            "a.by",
            "import os\n\ndef f():\n    return <selection>3600</selection>\n",
        )
        applyIntroduceConstant("HOUR")
        fixture.checkResult("import os\nHOUR = 3600\n\ndef f():\n    return HOUR\n")
    }

    @Test
    fun `introduce constant no trailing newline plain file`() {
        fixture.configureByText("a.by", "x = <selection>10</selection>")
        applyIntroduceConstant("TEN")
        fixture.checkResult("TEN = 10\n\nx = TEN")
    }

    // ------------------------------------------------------------------
    // Action enable/disable behaviour (drive update() only, never perform — performing
    // would pop a modal name dialog which throws in headless tests).
    // ------------------------------------------------------------------

    private fun updatePresentation(action: AnAction): com.intellij.openapi.actionSystem.Presentation {
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, fixture.editor)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(
                com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE,
                fixture.file!!.virtualFile,
            )
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation
    }

    @Test
    fun `extract action disabled without selection`() {
        fixture.configureByText("a.by", "x = 1\n")
        assertFalse(
            updatePresentation(ExtractVariableAction()).isEnabled,
            "should be disabled with no selection",
        )
    }

    @Test
    fun `extract action enabled with selection on by file`() {
        fixture.configureByText("a.by", "x = <selection>1</selection>\n")
        assertTrue(
            updatePresentation(ExtractVariableAction()).isEnabled,
            "should be enabled with selection on .by file",
        )
    }

    @Test
    fun `introduce constant action disabled on non-by file`() {
        fixture.configureByText("a.txt", "x = <selection>1</selection>\n")
        assertFalse(
            updatePresentation(IntroduceConstantAction()).isEnabled,
            "should be disabled on non-.by file",
        )
    }

    @Test
    fun `introduce constant action enabled with selection on by file`() {
        fixture.configureByText("a.by", "x = <selection>1</selection>\n")
        assertTrue(
            updatePresentation(IntroduceConstantAction()).isEnabled,
            "should be enabled with selection on .by file",
        )
    }
}
