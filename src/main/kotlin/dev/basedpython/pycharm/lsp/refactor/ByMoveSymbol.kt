package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiReference
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandlerDelegate
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByMovableSymbol
import dev.basedpython.pycharm.lsp.ext.ByMoveSymbolParams
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.CreateFilesParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileCreate
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent
import java.io.IOException
import java.net.URI
import java.nio.file.Path
import javax.swing.JComponent

/**
 * F6 on a top-level function, class or variable of a `.by` file — in its name, in a reference to it,
 * or anywhere in its definition, its body included — moves that definition to another module, through `by/prepareMoveSymbol` and `by/moveSymbol`,
 * as F6 on one in a `.py` file opens Move Module Members.
 *
 * Which definition the caret is on is `by`'s answer, and so is the move: what the definition takes
 * with it, which imports every reference in the project gets, and when it is refused because the
 * program would no longer run as it did. Anywhere else in the file — or with a `by` that does not
 * know the request, or none running — the caret is on nothing this moves, and F6 goes on to the
 * platform's handlers and [ByModuleMoveHandler], which move the file, as before.
 *
 * Only in an editor, and only for the element at the caret: the platform asks each delegate about
 * that element and then about each of its parents, and `by` is asked once.
 */
class ByMoveSymbolHandler : MoveHandlerDelegate() {

    override fun canMove(elements: Array<out PsiElement>, targetContainer: PsiElement?, reference: PsiReference?): Boolean = false

    override fun canMove(dataContext: DataContext): Boolean = false

    override fun tryToMove(element: PsiElement, project: Project, dataContext: DataContext?, reference: PsiReference?, editor: Editor?): Boolean {
        if (editor == null || element is PsiFileSystemItem) return false
        val psiFile = element.containingFile ?: return false
        if (!psiFile.language.isKindOf(BasedPythonLanguage)) return false
        val file = psiFile.virtualFile ?: return false
        if (psiFile.findElementAt(editor.caretModel.offset) !== element) return false

        val title = RefactoringBundle.message("move.title")
        val site = ByProjectRefactorings.site(project, editor, file, title) ?: return true
        val asked = ByProjectRefactorings.ask(site, title, "by/prepareMoveSymbol") { it.prepareMoveSymbol(site.params) }
        val symbol = when (asked) {
            is ByAsked.Answer -> asked.value
            // nothing here to move: the platform's handlers go on to the file
            ByAsked.None, ByAsked.Unsupported -> return false
            ByAsked.Failed, is ByAsked.Rejected -> {
                ByProjectRefactorings.error(project, editor, title, ByProjectRefactorings.unanswered(asked, "by/prepareMoveSymbol", "")!!)
                return true
            }
        }
        ByMoveSymbolDialog(project, site, psiFile, symbol).show()
        return true
    }

    override fun doMove(project: Project, elements: Array<out PsiElement>, targetContainer: PsiElement?, callback: MoveCallback?) = Unit
}

/**
 * Where to move one top-level definition: the module it goes to, as a path, as `.py`'s Move Module
 * Members asks — the definition's own file to start from, a file that does not exist yet created
 * when the move is made.
 */
internal class ByMoveSymbolDialog(
    project: Project,
    private val site: ByRefactoringSite,
    private val subject: PsiFile,
    private val symbol: ByMovableSymbol,
) : RefactoringDialog(project, true) {

    private val from: String = symbol.uri?.let(::pathOf) ?: site.file.presentableUrl

    private val target = TextFieldWithBrowseButton().apply {
        text = from
        addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
        textField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = validateButtons()
        })
    }

    init {
        title = BasedPythonBundle.message("refactoring.by.moveSymbol.title")
        init()
        validateButtons()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(BasedPythonBundle.message("refactoring.by.moveSymbol.what", kindWord(symbol.kind), "${symbol.module}.${symbol.name}")) }
        row(BasedPythonBundle.message("refactoring.by.moveSymbol.to")) { cell(target).align(AlignX.FILL) }
        row(BasedPythonBundle.message("refactoring.by.moveSymbol.from")) { label(from) }
    }

    override fun getPreferredFocusedComponent(): JComponent = target.textField

    override fun hasHelpAction(): Boolean = false

    override fun areButtonsValid(): Boolean = targetPath()?.let { it != Path.of(from) } == true

    override fun doAction() {
        val path = targetPath() ?: return
        val title = BasedPythonBundle.message("refactoring.by.moveSymbol.title")
        val existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
        if (existing?.isDirectory == true) {
            return CommonRefactoringUtil.showErrorMessage(title, BasedPythonBundle.message("refactoring.by.moveSymbol.directory", path), null, project)
        }
        // `by` moves into an existing module only: a new one is made first, and taken away again
        // when nothing is moved into it
        val module = existing ?: create(path, title)?.also(::announce) ?: return
        val edited = ByProjectRefactorings.askEdit(site, title, "by/moveSymbol") {
            it.moveSymbol(ByMoveSymbolParams(site.identifier, site.position, site.server.descriptor.getFileUri(module)))
        }
        val processor = ByProjectRefactorings.processor(project, title, subject, edited)
        if (processor == null) {
            if (existing == null) delete(module, title)
            return
        }
        invokeRefactoring(processor)
    }

    /** The path in the field, or `null` when it is not one. */
    private fun targetPath(): Path? =
        target.text.trim().takeIf { it.isNotEmpty() }?.let { runCatching { Path.of(FileUtil.toSystemDependentName(it)) }.getOrNull() }

    /** Makes an empty module at [path], with the directories above it; `null` when it cannot, said to the user. */
    private fun create(path: Path, title: String): VirtualFile? {
        var created: VirtualFile? = null
        CommandProcessor.getInstance().executeCommand(project, {
            try {
                created = WriteAction.compute<VirtualFile, IOException> {
                    val parent = VfsUtil.createDirectoryIfMissing(path.parent.toString())
                        ?: throw IOException(RefactoringBundle.message("cannot.create.directory"))
                    parent.createChildData(this, path.fileName.toString())
                }
            } catch (e: IOException) {
                CommonRefactoringUtil.showErrorMessage(title, e.message ?: RefactoringBundle.message("cannot.create.directory"), null, project)
            }
        }, title, null)
        return created
    }

    /**
     * Tells `by` about the module [create] just made, before it is asked to move anything into it.
     *
     * `by` answers from the file system as it last read it, and until it hears of the module it
     * answers that the module is not one anything can import (measured: every move into a module made
     * a moment before was refused). A client that makes a file says so with LSP's
     * `workspace/didCreateFiles`, which `by` registers for every module and folder and applies at
     * once, and in order: the request sent after it is answered about a project that has the module.
     * A `by` that does not register for it hears of the file as a watched file that was created,
     * which it applies the same way.
     */
    private fun announce(module: VirtualFile) {
        val uri = site.server.descriptor.getFileUri(module)
        val registered = site.server.initializeResult?.capabilities?.workspace?.fileOperations?.didCreate != null
        if (registered) {
            site.server.sendNotification { it.workspaceService.didCreateFiles(CreateFilesParams(listOf(FileCreate(uri)))) }
        } else {
            site.server.sendNotification {
                it.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(listOf(FileEvent(uri, FileChangeType.Created))))
            }
        }
    }

    /** Takes away the module [create] made for a move that did not happen. */
    private fun delete(module: VirtualFile, title: String) {
        if (!module.isValid || FileDocumentManager.getInstance().getDocument(module)?.textLength != 0) return
        CommandProcessor.getInstance().executeCommand(project, {
            try {
                val deleted = FileEvent(site.server.descriptor.getFileUri(module), FileChangeType.Deleted)
                WriteAction.run<IOException> { module.delete(this) }
                site.server.sendNotification { it.workspaceService.didChangeWatchedFiles(DidChangeWatchedFilesParams(listOf(deleted))) }
            } catch (_: IOException) {
                // an empty module left behind is all this costs
            }
        }, title, null)
    }

    private fun pathOf(uri: String): String? = runCatching { Path.of(URI.create(uri)).toString() }.getOrNull()

    /** How `.py`'s Move Module Members names each kind of definition. */
    private fun kindWord(kind: String?): String = BasedPythonBundle.message(
        when (kind) {
            "function" -> "refactoring.by.moveSymbol.kind.function"
            "class" -> "refactoring.by.moveSymbol.kind.class"
            else -> "refactoring.by.moveSymbol.kind.variable"
        },
    )
}
