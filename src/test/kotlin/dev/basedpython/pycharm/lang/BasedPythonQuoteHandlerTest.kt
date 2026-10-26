package dev.basedpython.pycharm.lang

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Test

/** Quotes typed in a real editor pair as PyCharm pairs them for `.py` (measured in 263.7528). */
@TestFixtures
class BasedPythonQuoteHandlerTest {

    private val fixture by codeInsightFixture()

    private fun type(before: String, typed: String, after: String) = onEdt {
        fixture.configureByText("a.by", before)
        fixture.type(typed)
        fixture.checkResult(after)
    }

    @Test
    fun `a quote gets its pair`() = type("x = <caret>\n", "\"", "x = \"<caret>\"\n")

    @Test
    fun `a prefixed quote gets its pair after the prefix`() {
        type("x = <caret>\n", "f\"", "x = f\"<caret>\"\n")
        type("x = <caret>\n", "rb'", "x = rb'<caret>'\n")
    }

    @Test
    fun `the third quote of a triple gets the closing triple`() {
        type("x = <caret>\n", "\"\"\"", "x = \"\"\"<caret>\"\"\"\n")
        type("x = <caret>\n", "'''", "x = '''<caret>'''\n")
        type("x = <caret>\n", "f\"\"\"", "x = f\"\"\"<caret>\"\"\"\n")
    }

    @Test
    fun `a closing quote typed over the one there steps over it`() {
        type("x = \"abc<caret>\"\n", "\"", "x = \"abc\"<caret>\n")
        type("x = f\"abc<caret>\"\n", "\"", "x = f\"abc\"<caret>\n")
        type("x = \"\"\"abc<caret>\"\"\"\n", "\"\"\"", "x = \"\"\"abc\"\"\"<caret>\n")
    }

    @Test
    fun `a quote before a word or after an escape is not paired`() {
        type("x = <caret>abc\n", "\"", "x = \"<caret>abc\n")
        type("x = \"a\\<caret>\"\n", "\"", "x = \"a\\\"<caret>\"\n")
    }
}
