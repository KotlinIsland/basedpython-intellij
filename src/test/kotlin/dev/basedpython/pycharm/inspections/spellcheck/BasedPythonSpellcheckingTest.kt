package dev.basedpython.pycharm.inspections.spellcheck

import com.intellij.openapi.util.TextRange
import com.intellij.grazie.spellcheck.GrazieSpellCheckingInspection
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.semantic.BySemanticTokens
import dev.basedpython.pycharm.lsp.semantic.ByToken
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which words in a `.by` file the spellchecker reports: comments, strings, and an identifier only
 * where it declares a name. Every use of a misspelt name used to be reported as well — 12 typos in
 * a file where Python reports 6, measured in PyCharm 263.7471.
 */
@TestFixtures
class BasedPythonSpellcheckingTest {

    private val fixture by codeInsightFixture()

    private fun typos(text: String, tokens: (String) -> List<ByToken>): List<String> {
        val file = fixture.configureByText("main.by", text)
        BySemanticTokens.getInstance(fixture.project).remember(file, tokens(text))
        fixture.enableInspections(GrazieSpellCheckingInspection())
        return fixture.doHighlighting()
            .filter { it.inspectionToolId == "SpellCheckingInspection" }
            .sortedBy { it.startOffset }
            .map { "${text.substring(it.startOffset, it.endOffset)}@${text.substring(0, it.startOffset).count { c -> c == '\n' } + 1}" }
    }

    private fun at(text: String, name: String, nth: Int = 0): TextRange {
        var index = -1
        repeat(nth + 1) { index = text.indexOf(name, index + 1) }
        return TextRange(index, index + name.length)
    }

    @Test
    fun `a misspelt name is reported where it is declared, not at every use`() = onEdt {
        val text = "def total(itemz):\n    countr = 0\n    for x in itemz:\n        countr += x\n    return countr\n"
        val found = typos(text) {
            listOf(
                ByToken(at(it, "total"), "function", setOf("definition")),
                ByToken(at(it, "itemz"), "parameter", setOf("definition", "functionScope")),
                ByToken(at(it, "countr"), "variable", setOf("definition", "functionScope")),
                ByToken(at(it, "x"), "variable", setOf("definition", "functionScope")),
                ByToken(at(it, "itemz", 1), "parameter", setOf("functionScope")),
                ByToken(at(it, "countr", 1), "variable", setOf("functionScope")),
                ByToken(at(it, "x", 1), "variable", setOf("functionScope")),
                ByToken(at(it, "countr", 2), "variable", setOf("functionScope")),
            )
        }
        assertEquals(listOf("itemz@1", "countr@2"), found)
    }

    @Test
    fun `comments and strings are still checked`() = onEdt {
        val text = "# a commnt\ns = \"a strng\"\n"
        val found = typos(text) { listOf(ByToken(at(it, "s"), "variable", setOf("definition"))) }
        assertEquals(listOf("commnt@1", "strng@2"), found)
    }
}
