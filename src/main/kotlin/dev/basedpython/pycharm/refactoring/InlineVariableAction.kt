package dev.basedpython.pycharm.refactoring

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Inline Variable refactoring for `.by` files (FEATURES.md §117).
 *
 * Finds the single `name = expr` assignment for the identifier under the caret, replaces every
 * later word-boundary usage of `name` with the parenthesized RHS, and deletes the assignment line.
 *
 * The refactoring is LSP-free and entirely text/heuristic driven via [InlineLogic]; this class is a
 * thin wrapper that resolves the caret offset, builds the plan, and applies it under a single write
 * command. When no valid plan can be produced (no/multiple assignments, invalid identifier, no
 * usages, multi-line RHS) the action shows an informational dialog and makes no destructive change.
 */
class InlineVariableAction : AnAction() {

    private val commandTitle = "Inline Variable"

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val editor = e.getData(CommonDataKeys.EDITOR)
        val onIdentifier = editor != null && caretIdentifier(editor) != null
        val isBy = file != null && !file.isDirectory && isByFile(file)
        e.presentation.isVisible = isBy
        e.presentation.isEnabled = isBy && onIdentifier
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val document = editor.document
        val text = document.charsSequence.toString()
        val caret = editor.caretModel.offset

        val name = InlineLogic.identifierAt(text, caret)
        if (name == null) {
            Messages.showInfoMessage(
                project,
                "Place the caret on a local variable to inline it.",
                commandTitle,
            )
            return
        }

        val plan = InlineLogic.planInline(text, caret)
        if (plan == null) {
            Messages.showInfoMessage(
                project,
                "Cannot inline '$name': it must have exactly one single-line assignment " +
                    "and at least one other usage.",
                commandTitle,
            )
            return
        }

        WriteCommandAction.runWriteCommandAction(project, commandTitle, null, {
            // Edits are sorted descending by start offset so earlier offsets stay valid.
            for (edit in plan.toEdits()) {
                document.replaceString(edit.start, edit.end, edit.replacement)
            }
        })
    }

    private fun caretIdentifier(editor: Editor): String? {
        val text = editor.document.charsSequence
        return InlineLogic.identifierAt(text, editor.caretModel.offset)
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
