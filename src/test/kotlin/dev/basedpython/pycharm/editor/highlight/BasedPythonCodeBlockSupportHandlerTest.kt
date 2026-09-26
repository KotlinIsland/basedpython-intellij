package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The `codeBlockSupportHandler` registration, exercised the way the platform reaches it: through
 * the extension point's own static entry points, from a real `.by` file and caret, with `by`'s
 * outline of the file put in by hand.
 */
@TestFixtures
class BasedPythonCodeBlockSupportHandlerTest {

    private val fixture by codeInsightFixture()

    private val ifElifElse: OutlineSpec.Suite.() -> Unit = {
        compound {
            clause("if a:") { simple("pass") }
            clause("elif b:") { simple("pass") }
            clause("else:") { call("other()") }
        }
        call("after()")
    }

    private fun markers(): List<String> =
        CodeBlockSupportHandler.findMarkersRanges(fixture.file, BasedPythonLanguage, fixture.caretOffset)
            .map { it.substring(fixture.file.text) }

    @Test
    fun `the clause keywords come back as markers`() = onEdt {
        fixture.configureByText("a.by", "if a:\n    pass\nel<caret>if b:\n    pass\nelse:\n    other()\n\nafter()\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document, ifElifElse)

        assertEquals(listOf("if", "elif", "else"), markers())
    }

    @Test
    fun `the block range reaches the end of the last branch`() = onEdt {
        fixture.configureByText("a.by", "i<caret>f a:\n    pass\nelif b:\n    pass\nelse:\n    other()\n\nafter()\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document, ifElifElse)

        val range = CodeBlockSupportHandler.findCodeBlockRange(fixture.editor, fixture.file)

        assertEquals("if a:\n    pass\nelif b:\n    pass\nelse:\n    other()", range.substring(fixture.file.text))
    }

    /** `match` is a name here, and the outline says so; nothing about the colon after it counts. */
    @Test
    fun `a soft keyword used as a name is no block`() = onEdt {
        fixture.configureByText("a.by", "mat<caret>ch: int = 1\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) { simple("match: int = 1") }

        assertEquals(emptyList<String>(), markers())
    }

    @Test
    fun `a statement with one keyword has nothing to pair with`() = onEdt {
        fixture.configureByText("a.by", "<caret>with a:\n    pass\n")
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            compound { clause("with a:") { simple("pass") } }
        }

        assertEquals(emptyList<String>(), markers())
    }

    @Test
    fun `without an answer for this text there is no block`() = onEdt {
        fixture.configureByText("a.by", "i<caret>f a:\n    pass\nelse:\n    pass\n")

        assertEquals(emptyList<String>(), markers())
    }
}
