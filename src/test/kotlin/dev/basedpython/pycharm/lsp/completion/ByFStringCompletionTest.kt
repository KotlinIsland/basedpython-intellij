package dev.basedpython.pycharm.lsp.completion

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Inside an f-string's replacement field `by`'s items are filtered by the identifier before the
 * caret, where the platform's own prefix for the string token, `f"{car`, dropped every one.
 */
@TestFixtures
class ByFStringCompletionTest {

    private val fixture by codeInsightFixture()

    /** Whether the caret, written `‸` in [line], is in a field's expression. */
    private fun inField(line: String): Boolean {
        val offset = line.indexOf('‸')
        val file = fixture.configureByText("a.by", line.replace("‸", ""))
        return ByFStringFields.isInFieldCode(file, offset)
    }

    @Test
    fun `a field's expression is code, the rest of the literal is not`() = onEdt {
        assertTrue(inField("x = f\"{car‸}\"\n"))
        assertTrue(inField("x = f\"{‸}\"\n"), "an empty field")
        assertTrue(inField("x = f\"{car‸\n"), "a field still being typed")
        assertTrue(inField("x = t\"{a + car‸}\"\n"), "a t-string")
        assertTrue(inField("x = f\"{x:{wid‸}}\"\n"), "a field in a format spec")
        assertFalse(inField("x = f\"ab‸c{x}\"\n"), "text")
        assertFalse(inField("x = f\"{x!r‸}\"\n"), "a conversion")
        assertFalse(inField("x = f\"{x:>1‸0}\"\n"), "a format spec")
        assertFalse(inField("x = \"{car‸}\"\n"), "not an f-string")
    }

    @Test
    fun `the prefix is the identifier before the caret`() {
        assertEquals("car", ByFStringFields.identifierBefore("f\"{car", 6))
        assertEquals("", ByFStringFields.identifierBefore("f\"{", 3))
    }
}
