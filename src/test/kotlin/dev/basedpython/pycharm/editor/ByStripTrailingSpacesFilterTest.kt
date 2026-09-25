package dev.basedpython.pycharm.editor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.StripTrailingSpacesFilter
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * What "strip trailing spaces on save" leaves of a `.by` file, saved the way the IDE saves one: the
 * platform's stripper, running every registered filter, with [ByStripTrailingSpacesFilterFactory]
 * reading the strings from an outline put in as `by` would answer it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByStripTrailingSpacesFilterTest {

    private val fixture by codeInsightFixture()

    private val q = "\"\"\""

    private val settings get() = EditorSettingsExternalizable.getInstance()
    private var mode: String? = null

    @BeforeEach
    fun rememberMode() {
        mode = settings.stripTrailingSpaces
        settings.stripTrailingSpaces = EditorSettingsExternalizable.STRIP_TRAILING_SPACES_WHOLE
    }

    @AfterEach
    fun restoreMode() {
        mode?.let { settings.stripTrailingSpaces = it }
    }

    private val document: Document get() = fixture.editor.document

    /** Opens [name] and types [text] into it, so every line is modified and the file unsaved. */
    private fun typed(text: String, name: String = "a.by") {
        fixture.configureByText(name, "")
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.setText(text) }
    }

    private fun save(): String {
        FileDocumentManager.getInstance().saveDocument(document)
        return document.text
    }

    @Test
    fun `code lines are stripped and the lines inside a triple-quoted string are not`() {
        typed("x = 1   \ns = $q\n    kept   \n    also\t\n    $q   \nt = 'one line'   \n")
        OutlineSpec.remember(fixture.project, document) {
            string("$q\n    kept   \n    also\t\n    $q", strippedIndent = 4)
            string("'one line'")
        }

        assertEquals("x = 1\ns = $q\n    kept   \n    also\t\n    $q\nt = 'one line'\n", save())
    }

    /**
     * The spaces after the opening quotes are the string's first characters, and stripping them
     * would also decide whether basedpython dedents it: a body has to start with the line break.
     */
    @Test
    fun `the line a string opens on keeps its spaces`() {
        typed("s = $q   \n    body\n    $q\n")
        OutlineSpec.remember(fixture.project, document) { string("$q   \n    body\n    $q") }

        assertEquals("s = $q   \n    body\n    $q\n", save())
    }

    @Test
    fun `an f-string keeps its spaces in the text and in its interpolations`() {
        typed("f = f$q{\n    value   \n}   \ntext   \n$q   \n")
        OutlineSpec.remember(fixture.project, document) {
            string("f$q{\n    value   \n}   \ntext   \n$q", interpolations = listOf("{\n    value   \n}"))
        }

        assertEquals("f = f$q{\n    value   \n}   \ntext   \n$q\n", save())
    }

    /** Where the quotes are is the outline's to say, whatever other quotes are between them. */
    @Test
    fun `quotes of the other kind do not open or close a string`() {
        typed("a = \"'''\"   \nb = '''\"x\"   \n\"   \n'''   \n")
        OutlineSpec.remember(fixture.project, document) {
            string("\"'''\"")
            string("'''\"x\"   \n\"   \n'''")
        }

        assertEquals("a = \"'''\"\nb = '''\"x\"   \n\"   \n'''\n", save())
    }

    @Test
    fun `a string spanning many lines keeps every one of them`() {
        val body = (1..200).joinToString("") { "line $it  \n" }
        typed("s = '''\n$body'''  \nafter  \n")
        OutlineSpec.remember(fixture.project, document) { string("'''\n$body'''") }

        assertEquals("s = '''\n$body'''\nafter\n", save())
    }

    /**
     * Saved between an edit and `by`'s answer about it: nothing says where the strings are now, so
     * no line is stripped, not even the code lines. The next save made with an answer in hand
     * strips as usual.
     */
    @Test
    fun `an edit the outline has not caught up with strips nothing`() {
        typed("x = 1   \ns = $q\n    kept   \n$q\n")
        OutlineSpec.remember(fixture.project, document) { string("$q\n    kept   \n$q") }
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "y = 2   \n") }

        assertEquals("y = 2   \nx = 1   \ns = $q\n    kept   \n$q\n", save())

        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "z = 3\n") }
        OutlineSpec.remember(fixture.project, document) { string("$q\n    kept   \n$q") }
        assertEquals("z = 3\ny = 2\nx = 1\ns = $q\n    kept   \n$q\n", save())
    }

    /**
     * No outline says "not now", not "never": the filter must not lean on the platform retrying a
     * `NOT_ALLOWED` document, which is the reverse of what the two constants promise.
     */
    @Test
    fun `with no outline for this text the filter postpones rather than forbids`() {
        typed("x = 1   \n")

        assertSame(
            StripTrailingSpacesFilter.POSTPONED,
            ByStripTrailingSpacesFilterFactory().createFilter(fixture.project, document),
        )
    }

    /**
     * In the default mode a save clears which lines were modified, so the lines of a save made
     * before `by`'s outline keep their spaces through later saves — a Save All with the outline in
     * hand included — until they are edited again, and then they are stripped.
     */
    @Test
    fun `in modified-lines mode a line saved before its outline keeps its spaces until it is edited again`() {
        settings.stripTrailingSpaces = EditorSettingsExternalizable.STRIP_TRAILING_SPACES_CHANGED
        fixture.configureByText("a.by", "x = 1\n")
        fixture.editor.caretModel.moveToOffset(0)
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(document.textLength, "y = 2   \n") }

        assertEquals("x = 1\ny = 2   \n", save())

        OutlineSpec.remember(fixture.project, document) {}
        FileDocumentManager.getInstance().saveAllDocuments()
        assertEquals("x = 1\ny = 2   \n", document.text)

        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(document.getLineEndOffset(1) - 3, "0") }
        OutlineSpec.remember(fixture.project, document) {}
        assertEquals("x = 1\ny = 20\n", save())
    }

    @Test
    fun `an outline by declined to give strips nothing`() {
        typed("x = 1   \ns = '''\n  kept  \n'''\n")
        ByOutlines.getInstance(fixture.project)
            .remember(document, ByOutline(document.modificationStamp, emptyList(), emptyList(), declined = true))

        assertEquals("x = 1   \ns = '''\n  kept  \n'''\n", save())
    }

    @Test
    fun `other files are stripped as before`() {
        typed("x = '''   \n'''   \n", name = "a.txt")

        assertEquals("x = '''\n'''\n", save())
    }
}
