package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `codeBlockSupportHandler` registration, exercised the way the platform reaches it: through
 * the extension point's own static entry points, from a real `.by` file and caret.
 *
 * [BlockClausesTest] covers what pairs with what; this covers that the handler is found for the
 * basedpython language and gets a usable element out of the flat PSI — `findMarkersRanges` hands it
 * the leaf under the caret, and `findCodeBlockRange` is what `Ctrl+Shift+M` navigates.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonCodeBlockSupportHandlerTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `the clause keywords come back as markers`() {
        fixture.configureByText(
            "a.by",
            """
            if a:
                pass
            el<caret>if b:
                pass
            else:
                pass
            """.trimIndent()
        )

        val ranges = CodeBlockSupportHandler.findMarkersRanges(
            fixture.file, BasedPythonLanguage, fixture.caretOffset
        )

        assertEquals(listOf("if", "elif", "else"), ranges.map { it.substring(fixture.file.text) })
    }

    @Test
    fun `the block range reaches the end of the last branch`() {
        fixture.configureByText(
            "a.by",
            """
            i<caret>f a:
                pass
            else:
                other()

            after()
            """.trimIndent()
        )

        val range = CodeBlockSupportHandler.findCodeBlockRange(fixture.editor, fixture.file)

        assertEquals(
            """
            if a:
                pass
            else:
                other()
            """.trimIndent(),
            range.substring(fixture.file.text)
        )
    }

    @Test
    fun `a caret off any keyword has no block`() {
        fixture.configureByText("a.by", "x = <caret>1\n")

        assertEquals(
            emptyList<String>(),
            CodeBlockSupportHandler.findMarkersRanges(
                fixture.file, BasedPythonLanguage, fixture.caretOffset
            ).map { it.substring(fixture.file.text) }
        )
    }
}
