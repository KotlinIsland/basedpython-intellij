package dev.basedpython.pycharm.refactoring

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.refactoring.ExtractionLogic.ExtractionPlan

/**
 * Shared scaffolding for the selection-driven refactorings. Subclasses supply a display title,
 * a default suggested name, and the [ExtractionPlan] for a given selection; this base handles
 * enable/disable state, prompting for a name, and applying the plan under a write command.
 */
abstract class AbstractExtractionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    /** Human-readable command/dialog title, e.g. "Extract Variable". */
    protected abstract val commandTitle: String

    /** Prompt shown in the name input dialog. */
    protected abstract val namePrompt: String

    /** Suggested name pre-filled in the dialog for the given (trimmed) expression. */
    protected abstract fun suggestName(expression: String): String

    /** Builds the concrete extraction plan for the validated [name]. */
    protected abstract fun buildPlan(
        text: CharSequence,
        selectionStart: Int,
        selectionEnd: Int,
        name: String,
    ): ExtractionPlan

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        val enabled = file != null && !file.isDirectory && isByFile(file) && hasSelection
        e.presentation.isEnabled = enabled
        e.presentation.isVisible = file != null && !file.isDirectory && isByFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) return

        val selStart = selectionModel.selectionStart
        val selEnd = selectionModel.selectionEnd
        val selectedText = selectionModel.selectedText ?: return
        if (selectedText.isBlank()) return

        val suggested = suggestName(selectedText.trim())
        val name = promptForName(project, suggested) ?: return
        if (!ExtractionLogic.isValidIdentifier(name)) {
            Messages.showErrorDialog(project, "'$name' is not a valid identifier.", commandTitle)
            return
        }

        applyPlan(project, editor, selStart, selEnd, name)
    }

    private fun promptForName(project: Project, suggested: String): String? {
        val input = Messages.showInputDialog(
            project,
            namePrompt,
            commandTitle,
            Messages.getQuestionIcon(),
            suggested,
            null,
        ) ?: return null
        return input.trim().ifEmpty { return null }
    }

    private fun applyPlan(
        project: Project,
        editor: Editor,
        selStart: Int,
        selEnd: Int,
        name: String,
    ) {
        val document = editor.document
        val text = document.charsSequence.toString()
        val plan = buildPlan(text, selStart, selEnd, name)

        WriteCommandAction.runWriteCommandAction(project, commandTitle, null, {
            // Apply higher offset first so the earlier offset stays valid.
            if (plan.insertOffset <= plan.replaceStart) {
                document.replaceString(plan.replaceStart, plan.replaceEnd, plan.replaceWith)
                document.insertString(plan.insertOffset, plan.insertText)
            } else {
                document.insertString(plan.insertOffset, plan.insertText)
                document.replaceString(plan.replaceStart, plan.replaceEnd, plan.replaceWith)
            }
        })
    }

    protected fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
