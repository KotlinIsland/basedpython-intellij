package dev.basedpython.pycharm.lsp.refactor

import com.intellij.ide.util.DirectoryUtil
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.ui.RefactoringDialog
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.IncorrectOperationException
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * Where to move `.by` modules and packages: the target directory, and whether to look for what names
 * them.
 *
 * The platform's own Move dialog for files is internal API, so a handler that builds its own
 * processor — which moving a package needs, see [ByModuleMoveHandler] — builds this one to go with
 * it. It asks what that dialog asks, and keeps its answer to "Search for references" in the same
 * setting, so the choice carries over between the two.
 */
internal class ByModuleMoveDialog(
    project: Project,
    private val elements: Array<PsiElement>,
    initialTarget: PsiDirectory?,
    private val performMove: (target: PsiDirectory, done: () -> Unit) -> Unit,
) : RefactoringDialog(project, true) {

    private val target = TextFieldWithBrowseButton().apply {
        text = initialTarget?.virtualFile?.presentableUrl.orEmpty()
        addBrowseFolderListener(project, FileChooserDescriptorFactory.createSingleFolderDescriptor())
        textField.document.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) = validateButtons()
        })
    }

    private val searchForReferences = JBCheckBox(
        BasedPythonBundle.message("refactoring.by.move.searchForReferences"),
        RefactoringSettings.getInstance().MOVE_SEARCH_FOR_REFERENCES_FOR_FILE,
    )

    init {
        title = RefactoringBundle.message("move.title")
        init()
        validateButtons()
    }

    override fun createCenterPanel(): JComponent = panel {
        row { label(what()) }
        row(BasedPythonBundle.message("refactoring.by.move.toDirectory")) { cell(target).align(AlignX.FILL) }
        row { cell(searchForReferences) }
    }

    override fun getPreferredFocusedComponent(): JComponent = target.textField

    override fun hasPreviewButton(): Boolean = false

    /** There is no help page to open. */
    override fun hasHelpAction(): Boolean = false

    override fun areButtonsValid(): Boolean = target.text.isNotBlank()

    override fun doAction() {
        RefactoringSettings.getInstance().MOVE_SEARCH_FOR_REFERENCES_FOR_FILE = searchForReferences.isSelected
        val path = FileUtil.toSystemIndependentName(target.text.trim())
        CommandProcessor.getInstance().executeCommand(project, {
            val directory = try {
                WriteAction.compute<PsiDirectory?, IncorrectOperationException> { DirectoryUtil.mkdirs(PsiManager.getInstance(project), path) }
            } catch (_: IncorrectOperationException) {
                null
            }
            if (directory == null) {
                CommonRefactoringUtil.showErrorMessage(title, RefactoringBundle.message("cannot.create.directory"), null, project)
            } else {
                performMove(directory) { closeOKAction() }
            }
        }, RefactoringBundle.message("move.title"), null)
    }

    private fun what(): String {
        val single = elements.singleOrNull() as? PsiFileSystemItem
        return when {
            single != null -> BasedPythonBundle.message(
                if (single is PsiFile) "refactoring.by.move.file" else "refactoring.by.move.directory",
                single.virtualFile.presentableUrl,
            )
            else -> BasedPythonBundle.message("refactoring.by.move.elements", elements.size)
        }
    }
}
