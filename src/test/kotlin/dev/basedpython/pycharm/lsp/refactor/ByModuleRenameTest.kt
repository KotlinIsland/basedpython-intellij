package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiManager
import com.intellij.refactoring.BaseRefactoringProcessor.ConflictsInTestsException
import com.intellij.refactoring.move.MoveHandler
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Renaming or moving a `.by` module or package goes through `by` for the imports that name it.
 *
 * No server runs here, and that is the case that matters most to state: a rename that cannot ask
 * `by` must say so before anything moves, rather than rename the file and leave every import naming
 * a module that has gone — which is what the platform's own file rename did.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByModuleRenameTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `a by file and a directory of them are renamed by this processor, other files are not`() {
        val module = fixture.addFileToProject("pkg/mod.by", "def g() -> int:\n    return 2\n")
        val notes = fixture.addFileToProject("docs/notes.txt", "hello\n")

        assertInstanceOf(ByModuleRenameProcessor::class.java, RenamePsiElementProcessor.forElement(module))
        assertInstanceOf(ByModuleRenameProcessor::class.java, RenamePsiElementProcessor.forElement(module.containingDirectory))
        assertFalse(RenamePsiElementProcessor.forElement(notes) is ByModuleRenameProcessor)
        assertFalse(RenamePsiElementProcessor.forElement(notes.containingDirectory) is ByModuleRenameProcessor)
    }

    @Test
    fun `moving a by file or package is this handler's, moving anything else is not`() {
        val module = fixture.addFileToProject("pkg/mod.by", "")
        val notes = fixture.addFileToProject("docs/notes.txt", "")
        val handler = MoveHandlerDelegate.EP_NAME.findExtensionOrFail(ByModuleMoveHandler::class.java)

        assertTrue(handler.canMove(arrayOf(module), null, null))
        assertTrue(handler.canMove(arrayOf(module.containingDirectory), null, null))
        assertFalse(handler.canMove(arrayOf(notes), null, null))
        assertFalse(handler.canMove(arrayOf(notes.containingDirectory), null, null))
    }

    @Test
    fun `renaming a module with no by running is a conflict, and nothing is renamed`() {
        val util = fixture.addFileToProject("util.by", "def helper() -> int:\n    return 1\n")
        fixture.addFileToProject("main.by", "import util\n")

        val conflict = assertThrows<ConflictsInTestsException> { fixture.renameElement(util, "helpers.by") }

        assertEquals(
            listOf("The by language server is not running, so the imports that name util.by cannot be updated and will name a module that no longer exists."),
            conflict.messages.toList(),
        )
        assertEquals("util.by", util.name)
    }

    @Test
    fun `moving a package with no by running is a conflict, and nothing moves`() {
        val module = fixture.addFileToProject("pkg/mod.by", "")
        val dest = fixture.addFileToProject("dest/keep.txt", "").containingDirectory
        val pkg = module.containingDirectory

        val conflict = assertThrows<ConflictsInTestsException> {
            MoveHandler.doMove(fixture.project, arrayOf(pkg), dest, null, null)
        }

        assertEquals(
            listOf("The by language server is not running, so the imports that name pkg cannot be updated and will name a module that no longer exists."),
            conflict.messages.toList(),
        )
        assertEquals("src", pkg.parentDirectory?.name)
    }

    @Test
    fun `by's edits become usages in their files and are applied last first`() {
        val main = fixture.addFileToProject("main.by", "import util\nfrom util import helper\n\nprint(util.helper())\n")
        val util = fixture.addFileToProject("util.by", "")
        val uri = "file:///project/main.by"
        val edit = WorkspaceEdit(
            mapOf(
                uri to listOf(
                    TextEdit(Range(Position(3, 6), Position(3, 10)), "helpers"),
                    TextEdit(Range(Position(0, 7), Position(0, 11)), "helpers"),
                    TextEdit(Range(Position(1, 5), Position(1, 9)), "helpers"),
                ),
                "file:///project/gone.by" to listOf(TextEdit(Range(Position(0, 0), Position(0, 1)), "x")),
            ),
        )

        val usages = ByImportEdit.of(fixture.project, edit, util) { if (it == uri) main.virtualFile else null }

        assertEquals(3, usages.size)
        assertTrue(usages.all { it.file == main && it.referencedElement == util && !it.isNonCodeUsage })
        WriteCommandAction.runWriteCommandAction(fixture.project) { ByImportEdit.apply(fixture.project, usages) }
        assertEquals(
            "import helpers\nfrom helpers import helper\n\nprint(helpers.helper())\n",
            PsiManager.getInstance(fixture.project).findFile(main.virtualFile)!!.text,
        )
    }

    @Test
    fun `a rename that is not a module's asks nothing and has no conflict`() {
        val notes = fixture.addFileToProject("notes.txt", "hello\n")

        fixture.renameElement(notes, "readme.txt")

        assertEquals("readme.txt", notes.name)
    }
}
