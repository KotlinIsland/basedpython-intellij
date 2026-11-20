package dev.basedpython.pycharm.run.django

import com.intellij.ide.IdeView
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidatorEx
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.util.getOffsetInDocument
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import dev.basedpython.pycharm.navigation.django.ByDjangoStructureService
import dev.basedpython.pycharm.project.ByDjangoScaffold
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * *New | Django App* in the Project view: the app `manage.py startapp` writes — `apps.py`, `models.py`,
 * `admin.py`, `views.py`, `tests.py`, `migrations/` — in the directory chosen, opening its
 * `models.py`, and a note to add it to `INSTALLED_APPS`, with the settings `by` names one click away.
 *
 * The files are [ByDjangoScaffold]'s rather than `startapp`'s own run: `by run` stages a program in
 * a tree of its own, and an app written there is not written in the project.
 */
internal class ByNewDjangoAppAction : ByDjangoProjectAction() {

    init {
        templatePresentation.icon = BasedPythonIcons.Logo
    }

    override fun update(e: AnActionEvent) {
        super.update(e)
        if (e.presentation.isVisible) e.presentation.isEnabledAndVisible = directory(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val directory = directory(e) ?: return
        val name = Messages.showInputDialog(
            project,
            BasedPythonBundle.message("django.newApp.prompt"),
            BasedPythonBundle.message("django.newApp.title"),
            BasedPythonIcons.Logo,
            "",
            Validator(directory),
        ) ?: return
        val created = WriteAction.compute<VirtualFile?, Throwable> {
            for ((path, content) in ByDjangoScaffold.appFiles(name)) {
                val file = VfsUtil.createDirectoryIfMissing(directory, path.substringBeforeLast('/'))
                    ?.createChildData(this, path.substringAfterLast('/')) ?: continue
                VfsUtil.saveText(file, content)
            }
            directory.findFileByRelativePath("$name/models.py")
        }
        created?.let { OpenFileDescriptor(project, it).navigate(true) }
        remind(project, name)
    }

    /** Says the app is not installed yet, offering the settings module `by` names, at `INSTALLED_APPS` when it can. */
    private fun remind(project: Project, name: String) {
        val settings = ByDjangoStructureService.getInstance(project).current()?.projects?.firstOrNull()?.settings
        val notification = NotificationGroupManager.getInstance().getNotificationGroup("basedpython")
            .createNotification(BasedPythonBundle.message("django.newApp.install", name), NotificationType.INFORMATION)
        val file = settings?.uri?.let(ByWorkspaceEditFiles::fileOf)
        if (file != null) {
            notification.addAction(NotificationAction.createSimpleExpiring(BasedPythonBundle.message("django.newApp.openSettings")) {
                val document = FileDocumentManager.getInstance().getDocument(file)
                val offset = document?.let { doc -> settings.range?.start?.let { getOffsetInDocument(doc, it) } } ?: 0
                OpenFileDescriptor(project, file, offset).navigate(true)
            })
        }
        notification.notify(project)
    }

    /** The directory the Project view has selected, or the one a file selected in it is in. */
    private fun directory(e: AnActionEvent): VirtualFile? {
        val view: IdeView? = e.getData(LangDataKeys.IDE_VIEW)
        view?.directories?.singleOrNull()?.virtualFile?.let { return it }
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
        return if (file.isDirectory) file else file.parent
    }

    private class Validator(private val directory: VirtualFile) : InputValidatorEx {
        override fun getErrorText(inputString: String): String? = when {
            inputString.isEmpty() -> null
            !ByDjangoScaffold.isAppName(inputString) -> BasedPythonBundle.message("django.newApp.invalid", inputString)
            directory.findChild(inputString) != null -> BasedPythonBundle.message("django.newApp.exists", inputString)
            else -> null
        }

        override fun checkInput(inputString: String): Boolean = inputString.isNotEmpty() && getErrorText(inputString) == null

        override fun canClose(inputString: String): Boolean = checkInput(inputString)
    }
}
