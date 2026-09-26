package dev.basedpython.pycharm.highlight

import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What [StringPartsAnnotator] colours, through the daemon, from `by`'s outline of the strings.
 */
@TestFixtures
class StringPartsAnnotatorTest {

    private val fixture by codeInsightFixture()

    private fun coloured(key: TextAttributesKey): List<String> {
        val text = fixture.editor.document.text
        return fixture.doHighlighting()
            .filter { it.forcedTextAttributesKey == key }
            .sortedBy { it.startOffset }
            .map { text.substring(it.startOffset, it.endOffset) }
    }

    @Test
    fun `escapes and interpolations are coloured where by puts them`() = onEdt {
        fixture.configureByText("a.by", "x = f\"a\\n{b!r:>{w}}{{c}}\"\ny = b\"\\x41\\u00e9\"\nz = r\"\\n\"\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            string("f\"a\\n{b!r:>{w}}{{c}}\"", interpolations = listOf("{b!r:>{w}}"), escapes = listOf("\\n"))
            string("b\"\\x41\\u00e9\"", escapes = listOf("\\x41", "\\u"))
            string("r\"\\n\"")
        }

        assertEquals(listOf("{b!r:>{w}}"), coloured(BasedPythonHighlightKeys.FSTRING_INTERP))
        assertEquals(listOf("\\n", "\\x41", "\\u"), coloured(BasedPythonHighlightKeys.STRING_ESCAPE))
    }

    @Test
    fun `without an answer from by nothing inside a string is coloured`() = onEdt {
        fixture.configureByText("b.by", "x = f\"a\\n{b}\"\n")

        assertEquals(emptyList<String>(), coloured(BasedPythonHighlightKeys.FSTRING_INTERP))
        assertEquals(emptyList<String>(), coloured(BasedPythonHighlightKeys.STRING_ESCAPE))
    }
}
