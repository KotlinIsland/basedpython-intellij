package dev.basedpython.pycharm.transpile

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.actions.ByCli
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Action: "Diff api.lock"
 *
 * Captures the current `api.lock`, runs `by generate-api-file` into a temp file, then shows a
 * DiffManager comparison so the user can see what public-API surface has changed.
 *
 * If `by generate-api-file` writes the result in-place (overwriting api.lock), we detect that and
 * restore the original content after diffing.
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
        val apiLockPath = cwd.resolve("api.lock")

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Diffing api.lock…", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true

                    // Snapshot current api.lock (may not yet exist)
                    val originalBytes: ByteArray? =
                        if (Files.exists(apiLockPath)) Files.readAllBytes(apiLockPath) else null
                    val originalText = originalBytes?.decodeToString() ?: ""

                    // Run generate into a temp file so we don't disturb the workspace.
                    // Strategy: copy existing api.lock aside, run the generator, read result,
                    // restore if it wrote in-place.
                    val tmpBackup = Files.createTempFile("api-lock-backup-", ".lock")
                    try {
                        if (originalBytes != null) {
                            Files.write(tmpBackup, originalBytes)
                        }

                        val out = ByCli.run(project, "generate-api-file", cwd = cwd) ?: return
                        if (out.exitCode != 0) {
                            ByCli.notifyError(
                                project,
                                "by generate-api-file failed",
                                out.stderr.ifBlank { "exit ${out.exitCode}" },
                            )
                            return
                        }

                        // Read newly-generated content: prefer stdout, fall back to api.lock on disk
                        val regeneratedText: String = when {
                            out.stdout.isNotBlank() -> out.stdout
                            Files.exists(apiLockPath) -> Files.readString(apiLockPath)
                            else -> ""
                        }

                        // Restore original if the generator wrote in-place and we had a prior copy
                        val diskNow = if (Files.exists(apiLockPath)) Files.readAllBytes(apiLockPath) else null
                        if (originalBytes != null && diskNow != null && !diskNow.contentEquals(originalBytes)) {
                            // Generator overwrote api.lock – restore original
                            Files.copy(tmpBackup, apiLockPath, StandardCopyOption.REPLACE_EXISTING)
                            val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(apiLockPath)
                            vf?.refresh(false, false)
                        }

                        // Show diff on EDT
                        ApplicationManager.getApplication().invokeLater {
                            showApiLockDiff(project, originalText, regeneratedText)
                        }
                    } finally {
                        Files.deleteIfExists(tmpBackup)
                    }
                }
            },
        )
    }

    private fun showApiLockDiff(project: com.intellij.openapi.project.Project, current: String, regenerated: String) {
        val factory = DiffContentFactory.getInstance()

        val currentVf = com.intellij.testFramework.LightVirtualFile("api.lock (current)", current)
        val regeneratedVf = com.intellij.testFramework.LightVirtualFile("api.lock (regenerated)", regenerated).also {
            it.isWritable = false
        }

        val leftContent = factory.create(project, currentVf)
        val rightContent = factory.create(project, regeneratedVf)

        val request = SimpleDiffRequest(
            "api.lock — current vs regenerated",
            leftContent,
            rightContent,
            "api.lock (current)",
            "api.lock (regenerated)",
        )

        DiffManager.getInstance().showDiff(project, request)
    }
}
