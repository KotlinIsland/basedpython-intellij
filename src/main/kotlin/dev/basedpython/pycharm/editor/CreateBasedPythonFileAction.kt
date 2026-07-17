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
            .addKind(BasedPythonBundle.message("newFile.kind.emptyFile"), BasedPythonFileType.INSTANCE.icon, "basedpython File")
            .addKind(BasedPythonBundle.message("newFile.kind.class"), BasedPythonFileType.INSTANCE.icon, "basedpython Class")
            .addKind(BasedPythonBundle.message("newFile.kind.dataClass"), BasedPythonFileType.INSTANCE.icon, "basedpython Data Class")
            .addKind(BasedPythonBundle.message("newFile.kind.protocol"), BasedPythonFileType.INSTANCE.icon, "basedpython Protocol")
    }

    override fun getActionName(directory: PsiDirectory?, newName: String, templateName: String?): String =
        BasedPythonBundle.message("newFile.actionName")
}
