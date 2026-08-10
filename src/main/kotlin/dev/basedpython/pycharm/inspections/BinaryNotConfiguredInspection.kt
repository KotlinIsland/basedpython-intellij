package dev.basedpython.pycharm.inspections

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.BasedPythonBinaries

/**
 * File-level weak warning when the `by` binary cannot be found on this machine.
 * This is a local convenience only — it does not duplicate any LSP diagnostic.
 */
class BinaryNotConfiguredInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "basedpython"
    override fun getDisplayName(): String = "by binary not configured"
    override fun getShortName(): String = "BasedPythonBinaryNotConfigured"

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor> {
        if (file !is BasedPythonFile) return ProblemDescriptor.EMPTY_ARRAY
        val project = file.project
        if (BasedPythonBinaries.isByAvailable(project)) return ProblemDescriptor.EMPTY_ARRAY

        val firstElement = file.firstChild ?: return ProblemDescriptor.EMPTY_ARRAY
        val descriptor = manager.createProblemDescriptor(
            firstElement,
            "The by binary was not found. Type checking and LSP features are unavailable.",
            OpenBasedPythonSettingsFix(),
            ProblemHighlightType.WEAK_WARNING,
            isOnTheFly
        )
        return arrayOf(descriptor)
    }

    private class OpenBasedPythonSettingsFix : LocalQuickFix {
        override fun getFamilyName(): String = "Open basedpython settings"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, "basedpython")
        }
    }
}
