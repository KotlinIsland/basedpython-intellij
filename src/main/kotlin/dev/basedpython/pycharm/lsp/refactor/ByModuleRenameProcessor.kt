package dev.basedpython.pycharm.lsp.refactor

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiFileProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.indexing.DumbModeAccessType
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Which files and directories moving would rename a `.by` module: the ones `by` is asked about.
 */
internal object ByModuleFiles {

    /** A `.by` source, or any other file the IDE reads as one (a `.py` in a basedpython project). */
    fun isModule(file: VirtualFile): Boolean = !file.isDirectory && file.fileType == BasedPythonFileType.INSTANCE

    /**
     * A directory with a `.by` source somewhere under it: a package, or a directory of them.
     *
     * Answered by the file type index, not by walking the tree — the Project view asks this for every
     * drop target a drag passes over. The index is one of the few that stays reliable while the IDE
     * indexes, which is what [DumbModeAccessType.RELIABLE_DATA_ONLY] is for.
     */
    fun holdsModules(project: Project, directory: VirtualFile): Boolean =
        DumbModeAccessType.RELIABLE_DATA_ONLY.ignoreDumbMode<Boolean, RuntimeException> {
            FileTypeIndex.containsFileOfType(
                BasedPythonFileType.INSTANCE,
                GlobalSearchScopesCore.directoryScope(project, directory, true),
            )
        }

    /** Whether moving or renaming [element] can change the name of a module. */
    fun isModuleOrPackage(element: PsiElement): Boolean = when (element) {
        is PsiFile -> element.virtualFile?.let(::isModule) == true
        is PsiDirectory -> holdsModules(element.project, element.virtualFile)
        else -> false
    }

    /**
     * The renames `by` is asked about for moving [elements] into [target], one per element: a
     * directory is sent as itself, since renaming a package renames every module in it, and `by`
     * answers for the folder. Files that are not modules are left out; they name no module.
     */
    fun movesInto(elements: Iterable<PsiElement>, target: VirtualFile): List<ByFileMove> =
        elements.mapNotNull { element ->
            val file = (element as? PsiFileSystemItem)?.virtualFile ?: return@mapNotNull null
            if (!file.isDirectory && !isModule(file)) return@mapNotNull null
            val from = file.toNioPathOrNull() ?: return@mapNotNull null
            val to = target.toNioPathOrNull()?.resolve(file.name) ?: return@mapNotNull null
            ByFileMove(from, to)
        }

    /** The rename of [element] to [newName], in the directory it is in. */
    fun renameOf(element: PsiFileSystemItem, newName: String): ByFileMove? {
        val from = element.virtualFile?.toNioPathOrNull() ?: return null
        return ByFileMove(from, from.resolveSibling(newName))
    }

    private fun VirtualFile.toNioPathOrNull() = runCatching { toNioPath() }.getOrNull()
}

/**
 * Refactor | Rename on a `.by` file, or on a directory of them, that also rewrites the imports naming
 * the module or package — which `by` works out, before anything is renamed.
 *
 * Everything else about the rename is the platform's own file rename, which this extends: the same
 * dialog, its "Search for references" option, and the references other languages find. `by`'s edits
 * join them as usages ([ByImportEdit]), so the preview lists them and one undo takes back both the
 * rename and the edits.
 *
 * When `by` cannot say which imports to change — it is not running, does not answer this request, or
 * did not answer — the rename says so as a conflict, and the user chooses whether to rename anyway,
 * rather than being handed a project whose imports name a module that has gone.
 */
class ByModuleRenameProcessor : RenamePsiFileProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean = ByModuleFiles.isModuleOrPackage(element)

    /**
     * Called by the platform off the EDT, under the rename's cancellable progress, with the name the
     * user settled on — the one moment that has both the new name and the old file still in place.
     */
    override fun findCollisions(
        element: PsiElement,
        newName: String,
        allRenames: MutableMap<out PsiElement, String>,
        result: MutableList<UsageInfo>,
    ) {
        super.findCollisions(element, newName, allRenames, result)
        if (element !is PsiFileSystemItem || !searchesForReferences(element)) return
        // asked even without a path — a file no server could name — so that no server is still said
        val answer = ByModuleRenames.ask(element.project, listOfNotNull(ByModuleFiles.renameOf(element, newName)))
        when (answer) {
            is ByImportRewrites.Edits -> result += ByImportEdit.of(element.project, answer.edit, element)
            ByImportRewrites.NoneNeeded -> Unit
            else -> result += ByImportsUnknown(element, importsUnknownMessage(answer, element.name)!!)
        }
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        val (imports, rest) = splitImportEdits(usages)
        super.renameElement(element, newName, rest, listener)
        ByImportEdit.apply(element.project, imports)
    }

    /** The dialog's "Search for references": unticked, nothing that names the file is looked for. */
    private fun searchesForReferences(element: PsiFileSystemItem): Boolean =
        if (element is PsiFile) {
            RefactoringSettings.getInstance().RENAME_SEARCH_FOR_REFERENCES_FOR_FILE
        } else {
            RefactoringSettings.getInstance().RENAME_SEARCH_FOR_REFERENCES_FOR_DIRECTORY
        }
}
