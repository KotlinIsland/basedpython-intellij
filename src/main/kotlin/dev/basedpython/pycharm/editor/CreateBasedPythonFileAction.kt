package dev.basedpython.pycharm.editor

import com.intellij.ide.actions.CreateFileFromTemplateAction
import com.intellij.ide.actions.CreateFileFromTemplateDialog
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDirectory
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle

class CreateBasedPythonFileAction : CreateFileFromTemplateAction(
    BasedPythonBundle.message("newFile.action.text"),
    BasedPythonBundle.message("newFile.action.description"),
    BasedPythonFileType.INSTANCE.icon,
) {
    override fun buildDialog(
        project: Project,
        directory: PsiDirectory,
        builder: CreateFileFromTemplateDialog.Builder,
    ) {
        builder
            .setTitle(BasedPythonBundle.message("newFile.dialog.title"))
            .addKind(BasedPythonBundle.message("newFile.kind.emptyFile"), BasedPythonFileType.INSTANCE.icon, "BasedPython File")
            .addKind(BasedPythonBundle.message("newFile.kind.class"), BasedPythonFileType.INSTANCE.icon, "BasedPython Class")
            .addKind(BasedPythonBundle.message("newFile.kind.dataClass"), BasedPythonFileType.INSTANCE.icon, "BasedPython Data Class")
            .addKind(BasedPythonBundle.message("newFile.kind.protocol"), BasedPythonFileType.INSTANCE.icon, "BasedPython Protocol")
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        BasedPythonBundle.message("newFile.actionName")
}
