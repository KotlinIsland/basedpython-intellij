package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The keyword highlighter has to be consulted before the platform's LSP one.
 *
 * `HighlightUsagesHandler.createCustomHandler` takes the first factory that returns non-null, and
 * `LspHighlightUsagesHandlerFactory` returns a handler for *every* caret position in a file whose
 * server advertises `documentHighlightProvider` — `by` does, and answers `null` for keywords. So
 * whichever factory sorts first decides whether keywords highlight at all in a live session.
 * Unregistered order happened to put this plugin's factory ahead; `order="first"` in plugin.xml
 * states it, and this test fails if either the attribute or that outcome goes away.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class KeywordHighlightRegistrationTest {

    @Suppress("unused")
    private val fixture by codeInsightFixture()

    @Test
    fun `the keyword factory precedes the lsp factory`() {
        val names = HighlightUsagesHandlerFactory.EP_NAME.extensionList.map { it.javaClass.name }
        val ours = names.indexOf(BasedPythonKeywordHighlightUsagesHandlerFactory::class.java.name)
        val lsp = names.indexOfFirst { it.endsWith("LspHighlightUsagesHandlerFactory") }

        assertTrue(ours >= 0) { "keyword factory is not registered: $names" }
        // The premise: if the platform ever stops shipping this factory, `order="first"` and this
        // test have nothing left to guard and both should be revisited.
        assertTrue(lsp >= 0) { "no LSP highlight usages factory to order against: $names" }
        assertTrue(ours < lsp) { "keyword factory runs after the LSP one: $names" }
    }
}
