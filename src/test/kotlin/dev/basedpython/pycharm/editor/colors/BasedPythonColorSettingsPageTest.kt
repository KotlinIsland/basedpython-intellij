package dev.basedpython.pycharm.editor.colors

import com.intellij.openapi.options.colors.RainbowColorSettingsPage
import com.intellij.testFramework.junit5.TestApplication
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.semantic.BasedPythonSemanticTokensMapping
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The basedpython colour page: a key for everything `by`'s tokens are coloured with, and the
 * per-language *Semantic highlighting* checkbox, which only a [RainbowColorSettingsPage] gets.
 */
@TestApplication
class BasedPythonColorSettingsPageTest {

    private val page = BasedPythonColorSettingsPage()

    /** The legend `by` sends: every token type, and every modifier. */
    private val types = listOf(
        "namespace", "class", "parameter", "selfParameter", "clsParameter", "variable", "property", "function",
        "method", "keyword", "string", "number", "decorator", "builtinConstant", "typeParameter", "comment",
        "operator", "regexp",
    )
    private val modifiers = listOf(
        "definition", "readonly", "async", "documentation", "defaultLibrary", "functionScope", "annotation",
        "dunder", "keywordArgument",
    )

    @Test
    fun `every key by's tokens are coloured with can be set on the page`() {
        val listed = page.attributeDescriptors.map { it.key }.toSet()
        val used = buildSet {
            for (type in types) {
                add(BasedPythonSemanticTokensMapping.keyFor(type))
                for (modifier in modifiers) add(BasedPythonSemanticTokensMapping.keyFor(type, listOf(modifier)))
                for (modifier in modifiers) add(BasedPythonSemanticTokensMapping.keyFor(type, listOf("definition", modifier)))
            }
        }.filterNotNull()
        val missing = used.filter { it !in listed }.map { it.externalName }
        assertEquals(emptyList<String>(), missing)
    }

    @Test
    fun `the page has the per-language semantic highlighting checkbox, for parameters and locals`() {
        assertTrue(RainbowColorSettingsPage::class.java.isAssignableFrom(BasedPythonColorSettingsPage::class.java))
        assertEquals(BasedPythonLanguage, page.language)
        assertTrue(page.isRainbowType(BasedPythonHighlightKeys.PARAMETER))
        assertTrue(page.isRainbowType(BasedPythonHighlightKeys.LOCAL_VARIABLE))
    }

    @Test
    fun `every tag in the demo stands for a key`() {
        val tags = Regex("<(\\w+)>").findAll(page.demoText).map { it.groupValues[1] }.toSet()
        val known = page.additionalHighlightingTagToDescriptorMap.keys
        assertEquals(emptySet<String>(), tags - known)
    }
}
