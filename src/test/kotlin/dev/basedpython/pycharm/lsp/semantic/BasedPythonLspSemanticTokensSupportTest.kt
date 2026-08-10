package dev.basedpython.pycharm.lsp.semantic

import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonLspSemanticTokensSupportTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `asks server for semantic tokens on by files`() {
        val psiFile = fixture.configureByText("a.by", "a = 1 cast int")
        assertTrue(
            BasedPythonLspSemanticTokensSupport().shouldAskServerForSemanticTokens(psiFile),
            "basedpython files must ask `by` for semantic tokens; the platform default returns " +
                "true only for TEXT/textmate files, so inheriting it stops the request entirely",
        )
    }

    /** The exact condition that made this bite: our language is neither of the two the default allows. */
    @Test
    fun `platform default would not ask for by files`() {
        val psiFile = fixture.configureByText("a.by", "a = 1 cast int")
        assertFalse(
            LspSemanticTokensSupport().shouldAskServerForSemanticTokens(psiFile),
            "if this ever becomes TEXT/textmate the override is redundant, but the language ID is " +
                "load-bearing elsewhere, so the override should stay either way",
        )
    }
}
