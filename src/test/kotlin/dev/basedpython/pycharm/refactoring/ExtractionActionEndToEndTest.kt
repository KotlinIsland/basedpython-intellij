package dev.basedpython.pycharm.refactoring

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * End-to-end tests that drive the document mutation produced by the extraction plans through a
 * real editor + document, mirroring exactly what the actions do inside their write command.
 *
 * We don't invoke the [AbstractExtractionAction] subclasses directly because they pop a modal
 * name-input dialog; instead we replicate the (tiny, deterministic) apply step here using the
 * pure [ExtractionLogic] plan, which is the only document-mutating code path the actions run.
 */
class ExtractionActionEndToEndTest : BasePlatformTestCase() {

    private fun applyExtractVariable(name: String) = applyPlan { text, s, e ->
        ExtractionLogic.planExtractVariable(text, s, e, name)
    }

    private fun applyIntroduceConstant(name: String) = applyPlan { text, s, e ->
        ExtractionLogic.planIntroduceConstant(text, s, e, name)
    }

    private fun applyPlan(plan: (String, Int, Int) -> ExtractionLogic.ExtractionPlan) {
        val editor: Editor = myFixture.editor
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

    fun `test extract variable at top level`() {
        myFixture.configureByText("a.by", "result = <selection>1 + 2</selection>\n")
        applyExtractVariable("tmp")
        myFixture.checkResult("tmp = 1 + 2\nresult = tmp\n")
    }

    fun `test extract variable inside function body`() {
        myFixture.configureByText(
            "a.by",
            "def f():\n    return <selection>a + b</selection>\n",
        )
        applyExtractVariable("s")
        myFixture.checkResult("def f():\n    s = a + b\n    return s\n")
    }

    fun `test extract variable deeply nested`() {
        myFixture.configureByText(
            "a.by",
            "def f():\n    if cond:\n        x = <selection>foo()</selection>\n",
        )
        applyExtractVariable("v")
        myFixture.checkResult("def f():\n    if cond:\n        v = foo()\n        x = v\n")
    }

    fun `test extract variable preserves tab indentation`() {
        myFixture.configureByText("a.by", "def f():\n\treturn <selection>a + b</selection>\n")
        applyExtractVariable("t")
        myFixture.checkResult("def f():\n\tt = a + b\n\treturn t\n")
    }

    fun `test extract variable no trailing newline`() {
        myFixture.configureByText("a.by", "result = <selection>x * y</selection>")
        applyExtractVariable("p")
        myFixture.checkResult("p = x * y\nresult = p")
    }

    fun `test extract variable partial expression in middle`() {
        myFixture.configureByText("a.by", "z = a + <selection>b * c</selection> + d\n")
        applyExtractVariable("m")
        myFixture.checkResult("m = b * c\nz = a + m + d\n")
    }

    fun `test extract variable whole rhs`() {
        myFixture.configureByText("a.by", "total = <selection>price + tax</selection>\n")
        applyExtractVariable("subtotal")
        myFixture.checkResult("subtotal = price + tax\ntotal = subtotal\n")
    }

    // ------------------------------------------------------------------
    // Introduce Constant
    // ------------------------------------------------------------------

    fun `test introduce constant at top of plain file`() {
        myFixture.configureByText("a.by", "x = <selection>42</selection>\n")
        applyIntroduceConstant("ANSWER")
        myFixture.checkResult("ANSWER = 42\n\nx = ANSWER\n")
    }

    fun `test introduce constant after single import`() {
        myFixture.configureByText("a.by", "import os\nx = <selection>magic</selection>\n")
        applyIntroduceConstant("MAGIC")
        myFixture.checkResult("import os\nMAGIC = magic\nx = MAGIC\n")
    }

    fun `test introduce constant after multiple imports`() {
        myFixture.configureByText(
            "a.by",
            "import os\nimport sys\nfrom a import b\ny = <selection>compute()</selection>\n",
        )
        applyIntroduceConstant("VALUE")
        myFixture.checkResult(
            "import os\nimport sys\nfrom a import b\nVALUE = compute()\ny = VALUE\n",
        )
    }

    fun `test introduce constant after leading comments`() {
        myFixture.configureByText(
            "a.by",
            "# header\n# coding: utf-8\nx = <selection>99</selection>\n",
        )
        applyIntroduceConstant("LIMIT")
        myFixture.checkResult("# header\n# coding: utf-8\nLIMIT = 99\nx = LIMIT\n")
    }

    fun `test introduce constant after comments then imports`() {
        myFixture.configureByText(
            "a.by",
            "# top\nimport os\nx = <selection>7</selection>\n",
        )
        applyIntroduceConstant("SEVEN")
        myFixture.checkResult("# top\nimport os\nSEVEN = 7\nx = SEVEN\n")
    }

    fun `test introduce constant from selection on later line`() {
        myFixture.configureByText(
            "a.by",
            "import os\n\ndef f():\n    return <selection>3600</selection>\n",
        )
        applyIntroduceConstant("HOUR")
        myFixture.checkResult("import os\nHOUR = 3600\n\ndef f():\n    return HOUR\n")
    }

    fun `test introduce constant no trailing newline plain file`() {
        myFixture.configureByText("a.by", "x = <selection>10</selection>")
        applyIntroduceConstant("TEN")
        myFixture.checkResult("TEN = 10\n\nx = TEN")
    }

    // ------------------------------------------------------------------
    // Action enable/disable behaviour (drive update() only, never perform — performing
    // would pop a modal name dialog which throws in headless tests).
    // ------------------------------------------------------------------

    private fun updatePresentation(action: AnAction): com.intellij.openapi.actionSystem.Presentation {
        val context = com.intellij.openapi.actionSystem.impl.SimpleDataContext.builder()
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.EDITOR, myFixture.editor)
            .add(com.intellij.openapi.actionSystem.CommonDataKeys.PROJECT, project)
            .add(
                com.intellij.openapi.actionSystem.CommonDataKeys.VIRTUAL_FILE,
                myFixture.file!!.virtualFile,
            )
            .build()
        val event = TestActionEvent.createTestEvent(action, context)
        action.update(event)
        return event.presentation
    }

    fun `test extract action disabled without selection`() {
        myFixture.configureByText("a.by", "x = 1\n")
        assertFalse(
            "should be disabled with no selection",
            updatePresentation(ExtractVariableAction()).isEnabled,
        )
    }

    fun `test extract action enabled with selection on by file`() {
        myFixture.configureByText("a.by", "x = <selection>1</selection>\n")
        assertTrue(
            "should be enabled with selection on .by file",
            updatePresentation(ExtractVariableAction()).isEnabled,
        )
    }

    fun `test introduce constant action disabled on non-by file`() {
        myFixture.configureByText("a.txt", "x = <selection>1</selection>\n")
        assertFalse(
            "should be disabled on non-.by file",
            updatePresentation(IntroduceConstantAction()).isEnabled,
        )
    }

    fun `test introduce constant action enabled with selection on by file`() {
        myFixture.configureByText("a.by", "x = <selection>1</selection>\n")
        assertTrue(
            "should be enabled with selection on .by file",
            updatePresentation(IntroduceConstantAction()).isEnabled,
        )
    }
}
