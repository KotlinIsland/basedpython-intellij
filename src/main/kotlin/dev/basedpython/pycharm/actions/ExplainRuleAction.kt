package dev.basedpython.pycharm.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import dev.basedpython.pycharm.inspections.explain.ExplainRule
import dev.basedpython.pycharm.inspections.explain.ExplainRuleFix
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Explain the rule of the diagnostic under the caret, or of a code typed into a prompt when there
 * is none.
 *
 * The code under the caret is the one the server attached to that diagnostic — the same one its
 * *Explain rule* quick fix ([ExplainRuleFix]) carries — so this finds the fix and takes its code.
 */
class ExplainRuleAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR)
        val code = editor?.let { ExplainRuleFix.codesAt(project, it, it.caretModel.offset).firstOrNull() }
            ?: promptForCode(project)
            ?: return
        ExplainRule.show(project, editor, code, e.getData(CommonDataKeys.VIRTUAL_FILE))
    }

    private fun promptForCode(project: Project): String? =
        Messages.showInputDialog(
            project,
            BasedPythonBundle.message("explainRule.prompt.message"),
            BasedPythonBundle.message("explainRule.prompt.title"),
            Messages.getQuestionIcon(),
        )?.trim()?.takeIf { it.isNotEmpty() }
}
