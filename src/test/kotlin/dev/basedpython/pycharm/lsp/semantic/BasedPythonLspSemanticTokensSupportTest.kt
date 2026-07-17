package dev.basedpython.pycharm.lsp.semantic

import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/**
 * The platform gates semantic-token requests behind
 * [LspSemanticTokensSupport.shouldAskServerForSemanticTokens], whose default implementation is, in
 * full, `language.id == "TEXT" || language.id == "textmate"` — it only asks for files with no
 * native support, assuming a language with its own lexer colours itself.
 *
 * basedpython registers a real Language, so inheriting that default means `by` is never asked and
 * every identifier keeps its lexer colour, while diagnostics and completion still work because they
 * aren't gated. The failure is silent: no exception, no log line.
 */
class BasedPythonLspSemanticTokensSupportTest : BasePlatformTestCase() {

    fun testAsksServerForSemanticTokensOnByFiles() {
        val psiFile = myFixture.configureByText("a.by", "a = 1 cast int")
        assertTrue(
            "basedpython files must ask `by` for semantic tokens; the platform default returns " +
                "true only for TEXT/textmate files, so inheriting it stops the request entirely",
            BasedPythonLspSemanticTokensSupport().shouldAskServerForSemanticTokens(psiFile),
        )
    }

    /** The exact condition that made this bite: our language is neither of the two the default allows. */
    fun testPlatformDefaultWouldNotAskForByFiles() {
        val psiFile = myFixture.configureByText("a.by", "a = 1 cast int")
        assertFalse(
            "if this ever becomes TEXT/textmate the override is redundant, but the language ID is " +
                "load-bearing elsewhere, so the override should stay either way",
            LspSemanticTokensSupport().shouldAskServerForSemanticTokens(psiFile),
        )
    }
}
