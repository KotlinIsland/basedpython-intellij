package dev.basedpython.pycharm.transpile

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import dev.basedpython.pycharm.actions.ByCli
import java.nio.file.Paths

/**
 * Action: "Diff api.lock"
 *
 * Shows the project's `api.lock` beside the one `by generate-api-file` would write now, so the
 * user can see what public-API surface has changed.
 *
 * The regenerated file is asked for on stdout (`--stdout`), so nothing on disk is touched. This
 * used to run the generator for real and then guess whether it had overwritten `api.lock`: when
 * there was no lockfile it left a new one behind, and when there was one it copied the original
 * back behind the VFS and any editor that had it open.
 */
class DiffApiLockAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project?.basePath != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val basePath = project.basePath ?: return
        val cwd = Paths.get(basePath)

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Diffing api.lock…", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    val out = ByCli.run(project, "generate-api-file", "--stdout", cwd = cwd) ?: return
                    if (out.exitCode != 0) {
                        ByCli.notifyError(
                            project,
                            "by generate-api-file failed",
                            out.stderr.ifBlank { "exit ${out.exitCode}" },
                        )
                        return
                    }

                    ApplicationManager.getApplication().invokeLater({
                        showApiLockDiff(project, cwd.resolve("api.lock"), out.stdout)
                    }, project.disposed)
                }
            },
        )
    }

    private fun showApiLockDiff(project: Project, apiLock: java.nio.file.Path, regenerated: String) {
        val factory = DiffContentFactory.getInstance()
        // The lockfile as the IDE holds it, unsaved edits included; empty when there is none yet.
        val current = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(apiLock)
            ?.let { factory.create(project, it) }
            ?: factory.createEmpty()
        val request = SimpleDiffRequest(
            "api.lock — current vs regenerated",
            current,
            factory.create(project, regenerated, PlainTextFileType.INSTANCE),
            "api.lock (current)",
            "api.lock (regenerated)",
        )
        DiffManager.getInstance().showDiff(project, request)
    }
}
