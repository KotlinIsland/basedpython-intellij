package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiReference
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.copy.CopyFilesOrDirectoriesHandler
import com.intellij.refactoring.move.MoveCallback
import com.intellij.refactoring.move.MoveHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import com.intellij.util.ui.IoErrorText

/**
 * Refactor | Move, and a drop in the Project view, for `.by` files and the directories holding
 * them: the platform's own file move, whose processor also asks `by` which imports the move breaks.
 *
 * ### Why a move handler, not a `moveFileHandler`
 *
 * `by` answers for the move as the user asked for it — this package goes there — and it needs the
 * package itself: `import pkg` names the directory, not any file in it. A `moveFileHandler` is asked
 * about one file at a time and is told only where the *top* of the move is going, so for a file
 * inside a moved directory it cannot tell which directory moved, and neither can the request it
 * would build. The processor is where the whole move is known, and constructing it is the move
 * handler's job; so this is the platform's handler building [ByModuleMoveProcessor] instead of the
 * plain one. The platform's dialog for it is internal API, which is why it asks through
 * [ByModuleMoveDialog].
 *
 * Only for moves that include a module or a directory holding one; anything else is left to the
 * platform's handlers. It is registered first because the platform's other file handler,
 * `MoveRelatedFilesHandler`, sits ahead of the plain one and, asked from an editor (F6), takes any
 * file at all: after it, this would never be asked about a module moved from its own editor.
 */
class ByModuleMoveHandler : MoveFilesOrDirectoriesHandler() {

    override fun canMove(elements: Array<out PsiElement>, targetContainer: PsiElement?, reference: PsiReference?): Boolean =
        super.canMove(elements, targetContainer, reference) && elements.any(ByModuleFiles::isModuleOrPackage)

    /** F6 in an editor: asked for the element at the caret and then each parent; only a module is ours. */
    override fun tryToMove(element: PsiElement, project: Project, dataContext: DataContext?, reference: PsiReference?, editor: Editor?): Boolean =
        ByModuleFiles.isModuleOrPackage(element) && super.tryToMove(element, project, dataContext, reference, editor)

    /** What `MoveFilesOrDirectoriesUtil.doMove` does, down to the processor it builds. */
    override fun doMove(project: Project, elements: Array<out PsiElement>, targetContainer: PsiElement?, callback: MoveCallback?) {
        val adjusted = adjustForMove(project, elements.map { it }.toTypedArray(), targetContainer) ?: return
        val target = targetContainer?.let { MoveFilesOrDirectoriesUtil.resolveToDirectory(project, it) ?: return }
        val initial = MoveFilesOrDirectoriesUtil.getInitialTargetDirectory(target, adjusted)
        if (ApplicationManager.getApplication().isUnitTestMode) {
            move(project, adjusted, requireNotNull(initial) { "no target directory" }, callback) {}
            return
        }
        ByModuleMoveDialog(project, adjusted, initial) { targetDirectory, done ->
            move(project, adjusted, targetDirectory, callback, done)
        }.show()
    }

    private fun move(
        project: Project,
        elements: Array<PsiElement>,
        target: PsiDirectory,
        callback: MoveCallback?,
        done: () -> Unit,
    ) = CommandProcessor.getInstance().executeCommand(project, {
        val toCheck = listOf<PsiElement>(target) + elements.map { if (it is PsiFileSystemItem && it.parent != null) it.parent!! else it }
        if (!CommonRefactoringUtil.checkReadOnlyStatus(project, toCheck, false)) return@executeCommand
        try {
            val choice = if (elements.size > 1 || elements[0] is PsiDirectory) intArrayOf(-1) else null
            val moving = elements.filter { element ->
                if (element is PsiFile &&
                    CopyFilesOrDirectoriesHandler.checkFileExist(target, choice, element, element.name, RefactoringBundle.message("command.name.move"))
                ) {
                    return@filter false
                }
                MoveFilesOrDirectoriesUtil.checkMove(element, target)
                true
            }
            if (moving.isEmpty()) {
                done()
                return@executeCommand
            }
            ByModuleMoveProcessor(project, moving.toTypedArray(), target, callback, done).run()
        } catch (e: IncorrectOperationException) {
            val cause = e.cause as? java.io.IOException ?: throw e
            CommonRefactoringUtil.showErrorMessage(RefactoringBundle.message("error.title"), IoErrorText.message(cause), "refactoring.moveFile", project)
        }
    }, MoveHandler.getRefactoringName(), null)
}

/**
 * The platform's move of files and directories, plus the edits `by` asks for to the imports naming
 * what moves.
 *
 * `by` is asked in [findUsages] — off the EDT, under the move's cancellable progress, and before
 * anything moves, which is when the question can be answered. Its edits are usages
 * ([ByImportEdit]) the preview shows, and they are applied in [performRefactoring], in the same
 * command as the move, once the move has happened. When `by` cannot say which imports to change,
 * that is a conflict the user is shown before anything moves.
 *
 * The platform's own reference search is left as it is, and still runs for every other language's
 * files in the move; it is skipped in dumb mode, as the platform skips it, but `by` is asked
 * regardless, since it needs no index of the IDE's.
 */
private class ByModuleMoveProcessor(
    project: Project,
    elements: Array<PsiElement>,
    private val target: PsiDirectory,
    callback: MoveCallback?,
    done: Runnable,
) : MoveFilesOrDirectoriesProcessor(
    project,
    elements,
    target,
    RefactoringSettings.getInstance().MOVE_SEARCH_FOR_REFERENCES_FOR_FILE && !DumbService.isDumb(project),
    false,
    false,
    callback,
    done,
) {
    private val asksBy = RefactoringSettings.getInstance().MOVE_SEARCH_FOR_REFERENCES_FOR_FILE

    /** Why the imports of the move are unknown, when they are; read when the conflicts are shown. */
    @Volatile
    private var importsUnknown: String? = null

    override fun findUsages(): Array<UsageInfo> {
        val platform = super.findUsages()
        importsUnknown = null
        if (!asksBy) return platform
        val moves = ByModuleFiles.movesInto(myElementsToMove.asList(), target.virtualFile)
        val moved = myElementsToMove.first()
        return when (val answer = ByModuleRenames.ask(myProject, moves)) {
            is ByImportRewrites.Edits -> platform + ByImportEdit.of(myProject, answer.edit, moved)
            ByImportRewrites.NoneNeeded -> platform
            else -> {
                importsUnknown = importsUnknownMessage(answer, (moved as PsiFileSystemItem).name)
                platform
            }
        }
    }

    override fun showConflicts(conflicts: MultiMap<PsiElement, String>, usages: Array<out UsageInfo>?): Boolean {
        importsUnknown?.let { conflicts.putValue(myElementsToMove.first(), it) }
        return super.showConflicts(conflicts, usages)
    }

    override fun performRefactoring(usages: Array<out UsageInfo>) {
        val (imports, rest) = splitImportEdits(usages)
        super.performRefactoring(rest)
        // the platform reports a move that failed and carries on; the imports follow only a move that happened
        if (myElementsToMove.all { (it as? PsiFileSystemItem)?.virtualFile?.parent == target.virtualFile }) {
            ByImportEdit.apply(myProject, imports)
        }
    }
}
