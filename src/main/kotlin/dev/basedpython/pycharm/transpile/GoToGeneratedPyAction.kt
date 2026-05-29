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
import java.nio.file.Paths

/**
 * Action: "Go to Generated .py"
 *
 * Resolves the current .by file's counterpart under `out/` (mirroring the relative path and
 * swapping the extension to .py).  If the file is missing, offers to run `by build` first.
 */
class GoToGeneratedPyAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            file != null && !file.isDirectory && isByFile(file) && e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val basePath = project.basePath ?: return

        val outPath = resolveOutPath(file, basePath) ?: run {
            ByCli.notifyError(project, "Go to Generated .py", "Could not resolve output path for ${file.name}.")
            return
        }

        val existing = LocalFileSystem.getInstance().findFileByNioFile(outPath)
        if (existing != null && existing.exists()) {
            openFile(project, existing)
            return
        }

        // File is missing – offer to build
        ApplicationManager.getApplication().invokeLater {
            val choice = Messages.showYesNoDialog(
                project,
                "Generated file not found:\n${outPath}\n\nRun `by build` to generate it?",
                "Go to Generated .py",
                Messages.getQuestionIcon(),
            )
            if (choice == Messages.YES) {
                runBuildThenOpen(project, basePath, outPath)
            }
        }
    }

    private fun runBuildThenOpen(project: Project, basePath: String, outPath: java.nio.file.Path) {
        val cwd = Paths.get(basePath)
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Running by build…", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val out = ByCli.run(project, "build", cwd = cwd) ?: return
                    if (out.exitCode != 0) {
                        ByCli.notifyError(project, "by build failed", out.stderr.ifBlank { "exit ${out.exitCode}" })
                        return
                    }

                    // Refresh VFS so IntelliJ sees the new file
                    val outDir = LocalFileSystem.getInstance()
                        .refreshAndFindFileByNioFile(outPath.parent ?: cwd)
                    if (outDir != null) VfsUtil.markDirtyAndRefresh(false, true, true, outDir)

                    val generated = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outPath)
                    if (generated == null) {
                        ByCli.notifyError(
                            project,
                            "Go to Generated .py",
                            "by build succeeded but ${outPath.fileName} was not found in out/.",
                        )
                        return
                    }
                    ApplicationManager.getApplication().invokeLater { openFile(project, generated) }
                }
            },
        )
    }

    private fun openFile(project: Project, file: VirtualFile) {
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    /**
     * Maps `<projectRoot>/some/sub/dir/foo.by`  →  `<projectRoot>/out/some/sub/dir/foo.py`
     * The `out/` directory is the standard `by build` output location.
     */
    private fun resolveOutPath(file: VirtualFile, basePath: String): java.nio.file.Path? {
        val base = Paths.get(basePath)
        val filePath = file.toNioPath()
        return try {
            val relative = base.relativize(filePath)
            val relativeStr = relative.toString()
            val withPyExt = relativeStr.replaceFirst(Regex("\\.by$", RegexOption.IGNORE_CASE), ".py")
            base.resolve("out").resolve(withPyExt)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
