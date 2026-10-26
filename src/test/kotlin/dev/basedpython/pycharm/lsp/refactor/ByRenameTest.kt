package dev.basedpython.pycharm.lsp.refactor

import com.intellij.codeInsight.template.impl.TemplateManagerImpl
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.util.TextRange
import com.intellij.refactoring.rename.RenameHandler
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.DocumentHighlightKind
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Shift+F6 in a `.by` file: where the name is and where else it is written are `by`'s answers
 * (`prepareRename`, `documentHighlight`); what is left here is editing every occurrence at once
 * and putting the old name back for `by`'s rename to start from.
 */
@TestFixtures
class ByRenameTest {

    private val fixture by codeInsightFixture()

    private val source = "def count(items):\n    width = len(items)\n    return width * 2 + width\n"

    private fun range(text: String, occurrence: Int = 0): TextRange {
        var offset = -1
        repeat(occurrence + 1) { offset = source.indexOf(text, offset + 1) }
        return TextRange(offset, offset + text.length)
    }

    private fun lsp(range: TextRange): Range {
        val lineStarts = source.indices.filter { it == 0 || source[it - 1] == '\n' }
        fun pos(offset: Int): Position {
            val line = lineStarts.indexOfLast { it <= offset }
            return Position(line, offset - lineStarts[line])
        }
        return Range(pos(range.startOffset), pos(range.endOffset))
    }

    private fun highlight(range: TextRange) = DocumentHighlight(lsp(range), DocumentHighlightKind.Text)

    @Test
    fun `the other occurrences are by's highlights of the name, in order, without the one at the caret`() = onEdt {
        val document = EditorFactory.getInstance().createDocument(source)
        val primary = range("width", 1)
        val highlights = listOf(range("width", 2), range("width", 0), primary, range("items", 1)).map(::highlight)

        val sites = ByRenameSites.of(document, "width", primary, highlights)

        // `items` is highlighted but is not the name: the template would overwrite it with the new one
        assertEquals(ByRenameSites("width", primary, listOf(range("width", 0), range("width", 2))), sites)
    }

    @Test
    fun `the plugin's handler renames in a by file, and nothing of the platform's LSP is asked`() = onEdt {
        fixture.configureByText("a.by", source)
        val handlers = RenameHandler.EP_NAME.extensionList.filterIsInstance<ByRenameHandler>()
        assertEquals(1, handlers.size)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, fixture.project)
            .add(CommonDataKeys.EDITOR, fixture.editor)
            .add(CommonDataKeys.PSI_FILE, fixture.file)
            .build()
        // no server runs in a test, and with none there is nothing to rename through
        assertFalse(handlers.single().isAvailableOnDataContext(context))
    }

    @Test
    fun `a file that is not basedpython is not the handler's`() = onEdt {
        fixture.configureByText("notes.txt", "width\n")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, fixture.project)
            .add(CommonDataKeys.EDITOR, fixture.editor)
            .add(CommonDataKeys.PSI_FILE, fixture.file)
            .build()
        assertFalse(ByRenameHandler().isAvailableOnDataContext(context))
    }

    @Test
    fun `typing the new name edits every occurrence, and Escape puts the old one back`() = onEdt {
        TemplateManagerImpl.setTemplateTesting(fixture.testRootDisposable)
        fixture.configureByText("a.by", source)
        val sites = ByRenameSites("width", range("width", 1), listOf(range("width", 0), range("width", 2)))

        ByInplaceRename(fixture.project, fixture.editor, fixture.file, sites).start()
        val state = checkNotNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertEquals(3, state.segmentsCount)
        // the name at the caret is the one being edited
        assertTrue(state.currentVariableRange!!.contains(range("width", 1)))

        fixture.type("size")
        assertEquals("def count(items):\n    size = len(items)\n    return size * 2 + size\n", fixture.editor.document.text)

        state.gotoEnd(true)
        assertNull(TemplateManagerImpl.getTemplateState(fixture.editor))
        assertEquals(source, fixture.editor.document.text)
    }
}
