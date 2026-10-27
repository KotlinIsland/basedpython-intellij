package dev.basedpython.pycharm.lsp.completion

import com.intellij.codeInsight.template.postfix.templates.LanguagePostfixTemplate
import com.intellij.testFramework.junit5.TestApplication
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * basedpython's postfix templates are `by`'s completions, `for` with the detail `postfix` replacing all
 * of `xs.for`; the switch for them is `by`'s `completions.postfixTemplates` (see LspServerDescriptorTest).
 */
@TestApplication
class ByPostfixCompletionTest {

    @Test
    fun `the plugin has no postfix templates of its own beside by's`() {
        assertEquals(emptyList<Any>(), LanguagePostfixTemplate.LANG_EP.allForLanguage(BasedPythonLanguage))
    }
}
