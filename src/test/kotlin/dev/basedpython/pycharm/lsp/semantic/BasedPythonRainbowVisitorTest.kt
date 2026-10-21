package dev.basedpython.pycharm.lsp.semantic

import com.intellij.codeHighlighting.RainbowHighlighter
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Rainbow parameters and locals: reading `by`'s tokens ([ByLocalNames.read]), and the visitor
 * colouring what they name in a real highlighting pass.
 */
@TestFixtures
class BasedPythonRainbowVisitorTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `only variable and parameter tokens marked functionScope are locals`() {
        val text = "def f(a):\n    b = a\n    return c\n"
        // relative-encoded: line, start, length, type, modifiers. Types: 0 variable, 1 parameter,
        // 2 function; the functionScope modifier is bit 3.
        val data = listOf(
            0, 4, 1, 2, 0, //  f, a function
            0, 2, 1, 1, 8, //  a, a parameter
            1, 4, 1, 0, 8, //  b, a local
            0, 4, 1, 1, 8, //  a, the parameter again
            1, 11, 1, 0, 0, // c, a global
        )
        val names = ByLocalNames.read(text, data, variableType = 0, parameterType = 1, functionScopeBit = 3)
        assertEquals(
            listOf("a" to true, "b" to false, "a" to true),
            names.map { it.range.substring(text) to it.isParameter },
        )
    }

    @Test
    fun `each local is coloured, the same name the same colour, and nothing else is`() = onEdt {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val wasEnabled = RainbowHighlighter.isRainbowEnabled(scheme, BasedPythonLanguage)
        RainbowHighlighter.setRainbowEnabled(scheme, BasedPythonLanguage, true)
        try {
            val text = "def f(alpha):\n    beta = alpha\n    return gamma\n"
            val file = fixture.configureByText("main.by", text)
            fun at(name: String, nth: Int = 0): TextRange {
                var index = -1
                repeat(nth + 1) { index = text.indexOf(name, index + 1) }
                return TextRange(index, index + name.length)
            }
            ByLocalNames.getInstance(fixture.project).remember(
                file,
                listOf(ByLocalName(at("alpha"), true), ByLocalName(at("beta"), false), ByLocalName(at("alpha", 1), true)),
            )

            val rainbow = fixture.doHighlighting().filter { it.type == RainbowHighlighter.RAINBOW_ELEMENT }
            assertEquals(listOf("alpha", "beta", "alpha"), rainbow.sortedBy { it.startOffset }.map { text.substring(it.startOffset, it.endOffset) })
            val (first, _, second) = rainbow.sortedBy { it.startOffset }
            assertEquals(first.forcedTextAttributes, second.forcedTextAttributes)
        } finally {
            RainbowHighlighter.setRainbowEnabled(scheme, BasedPythonLanguage, wasEnabled)
        }
    }
}
