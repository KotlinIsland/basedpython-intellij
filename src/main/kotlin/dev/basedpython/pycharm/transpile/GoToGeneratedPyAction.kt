package dev.basedpython.pycharm.transpile

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Action: "Go to Generated .py"
 *
 * Opens the file `by build` writes the current `.by` file to — where that is, is `by`'s answer
 * ([ByBuildOutputs]). If the file is missing, offers to run `by build` first — see [buildAt].
 */
class GoToGeneratedPyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory && file.isInLocalFileSystem && isByFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Finding the generated .py…", true) {
                override fun run(indicator: ProgressIndicator) {
                    val answer = ByBuildOutputs.getInstance(project).of(file)
                    val generated = answer?.generated
                    val root = answer?.projectRoot
                    if (generated == null || root == null) {
                        ByCli.notifyError(
                            project,
                            "Go to Generated .py",
                            "`by` did not say where ${file.name} is built to. Is the `by` language server running?",
                        )
                        return
                    }
                    val existing = LocalFileSystem.getInstance().refreshAndFindFileByPath(generated)
                    ApplicationManager.getApplication().invokeLater({
                        if (existing != null) openFile(project, existing) else offerBuild(project, Paths.get(root), Paths.get(generated))
                    }, project.disposed)
                }
            },
        )
    }

    private fun offerBuild(project: Project, root: Path, generated: Path) {
        val choice = Messages.showYesNoDialog(
            project,
            "Generated file not found:\n$generated\n\nRun by build to generate it?",
            "Go to Generated .py",
            Messages.getQuestionIcon(),
        )
        if (choice == Messages.YES) runBuildThenOpen(project, root, generated)
    }

    private fun runBuildThenOpen(project: Project, root: Path, generated: Path) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Running by build…", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val out = buildAt(project, root, "Running by build…") ?: return
                    if (out.exitCode != 0) {
                        ByCli.notifyError(project, "by build failed", out.stderr.ifBlank { "exit ${out.exitCode}" })
                        return
                    }

                    // Refresh VFS so IntelliJ sees the new file
                    val outDir = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(generated.parent ?: root)
                    if (outDir != null) VfsUtil.markDirtyAndRefresh(false, true, true, outDir)

                    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(generated)
                    if (file == null) {
                        ByCli.notifyError(
                            project,
                            "Go to Generated .py",
                            "by build succeeded but did not write $generated.",
                        )
                        return
                    }
                    ApplicationManager.getApplication().invokeLater({ openFile(project, file) }, project.disposed)
                }
            },
        )
    }

    private fun openFile(project: Project, file: VirtualFile) {
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
