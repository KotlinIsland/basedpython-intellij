package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.refactoring.suggested.SuggestedRefactoringState
import com.intellij.refactoring.suggested.SuggestedRefactoringSupport
import com.intellij.refactoring.suggested.SuggestedRenameData
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The suggested rename of a `.by` declaration: which tokens are declarations is `by`'s outline, and
 * the rename is `by`'s edit. What is left here is reading the outline right and making the edit.
 */
@TestFixtures
class BySuggestedRenameTest {

    private val fixture by codeInsightFixture()

    private val source = "def area(width, height):\n    return width * height\n\narea(1, 2)\n"

    private val support: SuggestedRefactoringSupport
        get() = checkNotNull(SuggestedRefactoringSupport.forLanguage(BasedPythonLanguage))

    private fun configure(outline: Boolean = true) {
        fixture.configureByText("a.by", source)
        if (outline) {
            OutlineSpec.remember(fixture.project, fixture.editor.document) {
                def("def area(width, height):", parameters = listOf("width", "height")) {
                    simple("return width * height")
                }
                call("area(1, 2)")
            }
        }
    }

    /** The token at the [occurrence]th [text] in the file. */
    private fun token(text: String, occurrence: Int = 0): PsiElement {
        var offset = -1
        repeat(occurrence + 1) { offset = source.indexOf(text, offset + 1) }
        return checkNotNull(fixture.file.findElementAt(offset))
    }

    @Test
    fun `the support is registered for basedpython`() = onEdt {
        assertInstanceOf(BySuggestedRefactoringSupport::class.java, support)
    }

    @Test
    fun `a definition's name and its parameters are declarations`() = onEdt {
        configure()
        assertTrue(support.isAnchor(token("area")))
        assertTrue(support.isAnchor(token("width")))
        assertTrue(support.isAnchor(token("height")))
    }

    /** Editing a use changes what it refers to, which is not a rename of anything. */
    @Test
    fun `a use of a name is not a declaration`() = onEdt {
        configure()
        assertFalse(support.isAnchor(token("width", occurrence = 1)))
        assertFalse(support.isAnchor(token("area", occurrence = 1)))
        assertFalse(support.isAnchor(fixture.file))
    }

    /** Before `by` has outlined the text on screen nothing is a declaration, rather than a guess. */
    @Test
    fun `without an outline nothing is a declaration`() = onEdt {
        configure(outline = false)
        assertFalse(support.isAnchor(token("area")))
    }

    @Test
    fun `a declaration's signature is its name`() = onEdt {
        configure()
        val signature = support.stateChanges.signature(token("area"), null)
        assertEquals(SuggestedRefactoringSupport.Signature.create("area", null, emptyList(), null), signature)
    }

    /**
     * The platform puts the old name back and renames the declaration it is handed; that declaration
     * follows the name through edits, and putting the name back rewrites the name and nothing else.
     */
    @Test
    fun `the rename is offered on a declaration that puts the old name back`() = onEdt {
        configure()
        val anchor = token("area")
        val state = checkNotNull(support.stateChanges.createInitialState(anchor))
        val renamed = withNewName(state, "surface")
        val data = support.availability.detectAvailableRefactoring(renamed) as SuggestedRenameData
        assertEquals("area", data.oldName)
        assertEquals("surface", data.newName)

        WriteCommandAction.runWriteCommandAction(fixture.project) { data.declaration.setName("area") }
        assertEquals(source, fixture.editor.document.text)
        assertEquals(TextRange(4, 8), data.declaration.textRange)
    }

    /** The rename's usages are `by`'s edits, the declaration among them, and renaming makes them. */
    @Test
    fun `the rename makes by's edits`() = onEdt {
        configure()
        val declaration = ByRenamedName(fixture.file, fixture.editor.document, TextRange(4, 8))
        val processor = RenamePsiElementProcessor.forElement(declaration)
        assertInstanceOf(ByRenamedNameProcessor::class.java, processor)

        val edit = WorkspaceEdit(
            mapOf(
                "file:///a.by" to listOf(
                    TextEdit(Range(Position(0, 4), Position(0, 8)), "surface"),
                    TextEdit(Range(Position(3, 0), Position(3, 4)), "surface"),
                ),
            ),
        )
        val usages = ByImportEdit.of(fixture.project, edit, declaration) { fixture.file.virtualFile }
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            processor.renameElement(declaration, "surface", usages.toTypedArray(), null)
        }
        assertEquals("def surface(width, height):\n    return width * height\n\nsurface(1, 2)\n", fixture.editor.document.text)
    }

    /** [state] as it is once the declaration's name reads [name]. */
    private fun withNewName(state: SuggestedRefactoringState, name: String): SuggestedRefactoringState {
        val document = fixture.editor.document
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.replaceString(4, 8, name) }
        PsiDocumentManager.getInstance(fixture.project).commitDocument(document)
        val anchor = checkNotNull(fixture.file.findElementAt(4))
        return support.stateChanges.updateState(state, anchor)
    }
}
