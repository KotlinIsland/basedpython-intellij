package dev.basedpython.pycharm.lsp.completion

import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import com.intellij.codeInsight.template.postfix.templates.LanguagePostfixTemplate
import com.intellij.testFramework.junit5.TestApplication
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.eclipse.lsp4j.CompletionItem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * basedpython's postfix templates are `by`'s completions, `for` with the detail `postfix` replacing all
 * of `xs.for`, listed unless basedpython's own switch says not.
 */
@TestApplication
class ByPostfixCompletionTest {

    @Test
    fun `the plugin has no postfix templates of its own beside by's`() {
        assertEquals(emptyList<Any>(), LanguagePostfixTemplate.LANG_EP.allForLanguage(BasedPythonLanguage))
    }

    @Test
    fun `a postfix template is the completion by details as one`() {
        assertTrue(ByPostfixCompletion.isPostfix(CompletionItem("for").apply { detail = "postfix" }))
        assertFalse(ByPostfixCompletion.isPostfix(CompletionItem("append").apply { detail = "bound method list[int].append(object: int, /) -> None" }))
        assertFalse(ByPostfixCompletion.isPostfix(CompletionItem("for")), "the keyword")
    }

    @Test
    fun `listed unless basedpython's postfix templates are switched off`() {
        val settings = BasedPythonAppSettings()
        assertTrue(ByPostfixCompletion.offered(settings))
        settings.offerPostfixTemplates = false
        assertFalse(ByPostfixCompletion.offered(settings))
    }
}
