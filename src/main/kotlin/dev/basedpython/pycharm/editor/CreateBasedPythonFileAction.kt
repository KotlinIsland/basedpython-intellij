package dev.basedpython.pycharm.editor

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import dev.basedpython.pycharm.lang.BasedPythonFileType

class CreateBasedPythonFileAction : CreateFileFromTemplateAction(
    "BasedPython File",
    "Create new BasedPython file",
    BasedPythonFileType.INSTANCE.icon,
) {
    override fun buildDialog(
        project: Project,
        directory: PsiDirectory,
        builder: CreateFileFromTemplateDialog.Builder,
    ) {
        builder
            .setTitle("New BasedPython File")
            .addKind("Empty file", BasedPythonFileType.INSTANCE.icon, "BasedPython File")
            .addKind("Class", BasedPythonFileType.INSTANCE.icon, "BasedPython Class")
            .addKind("Data class", BasedPythonFileType.INSTANCE.icon, "BasedPython Data Class")
            .addKind("Protocol", BasedPythonFileType.INSTANCE.icon, "BasedPython Protocol")
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        "Create BasedPython File"
}
