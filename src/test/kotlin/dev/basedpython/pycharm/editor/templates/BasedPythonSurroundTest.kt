package dev.basedpython.pycharm.editor.templates

import com.intellij.codeInsight.generation.surroundWith.SurroundWithHandler
import com.intellij.lang.LanguageSurrounders
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Surround With in a `.by` file, with `by`'s outline put in by hand: statement surrounders for whole
 * statements, expression ones inside a statement. Every surrounder used to be offered for any
 * selection, `if` and `while` taking it as their condition.
 */
@TestFixtures
class BasedPythonSurroundTest {

    private val fixture by codeInsightFixture()

    private fun configure(text: String, outline: OutlineSpec.Suite.() -> Unit) {
        fixture.configureByText("a.by", text)
        OutlineSpec.remember(fixture.project, fixture.editor.document, outline)
    }

    /** The surrounders offered for the selection, by the descriptors that have something to surround. */
    private fun offered(): List<String> {
        val selection = fixture.editor.selectionModel
        return LanguageSurrounders.INSTANCE.allForLanguage(BasedPythonLanguage)
            .filter { it.getElementsToSurround(fixture.file, selection.selectionStart, selection.selectionEnd).isNotEmpty() }
            .flatMap { it.surrounders.map { surrounder -> surrounder.templateDescription } }
    }

    private fun surround(description: String) {
        val selection = fixture.editor.selectionModel
        val surrounder = LanguageSurrounders.INSTANCE.allForLanguage(BasedPythonLanguage)
            .filter { it.getElementsToSurround(fixture.file, selection.selectionStart, selection.selectionEnd).isNotEmpty() }
            .flatMap { it.surrounders.toList() }
            .single { it.templateDescription == description }
        SurroundWithHandler.invoke(fixture.project, fixture.editor, fixture.file, surrounder)
    }

    private val statements: OutlineSpec.Suite.() -> Unit = {
        def("def f():") {
            simple("x = 1")
            simple("y = x + 2")
        }
    }

    @Test
    fun `whole statements are offered the statement surrounders only`() = onEdt {
        configure("def f():\n<selection>    x = 1\n    y = x + 2\n</selection>", statements)
        assertEquals(listOf("if", "while", "for", "with", "try / except", "try / finally"), offered())
    }

    @Test
    fun `part of a statement is offered the expression surrounders only`() = onEdt {
        configure("def f():\n    x = 1\n    y = <selection>x + 2</selection>\n", statements)
        assertEquals(listOf("( ... )", "[ ... ]"), offered())
    }

    @Test
    fun `if puts the statements in its body, and selects the condition to type`() = onEdt {
        configure("def f():\n<selection>    x = 1\n    y = x + 2\n</selection>", statements)
        surround("if")
        fixture.checkResult("def f():\n    if <selection>True</selection>:\n        x = 1\n        y = x + 2\n")
    }

    @Test
    fun `try and finally go around the statements at their indent`() = onEdt {
        configure("def f():\n    <selection>x = 1</selection>\n    y = x + 2\n", statements)
        surround("try / finally")
        fixture.checkResult("def f():\n    try:\n        x = 1\n    finally:\n        <selection>pass</selection>\n    y = x + 2\n")
    }

    @Test
    fun `parentheses go around the expression`() = onEdt {
        configure("def f():\n    x = 1\n    y = <selection>x + 2</selection>\n", statements)
        surround("( ... )")
        fixture.checkResult("def f():\n    x = 1\n    y = (<selection>x + 2</selection>)\n")
    }
}
