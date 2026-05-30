package dev.basedpython.pycharm.format

import com.intellij.lang.ImportOptimizer
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Optimize Imports (Ctrl+Alt+O) for `.by` files.
 *
 * Strategy:
 *   1. If the buff LSP server is running the IDE will have already routed
 *      `source.organizeImports` through it (the LSP advertises that code action).
 *   2. This `ImportOptimizer` is the CLI fallback: it runs
 *      `buff check --fix --select I <file>` in the background, which applies
 *      all isort-compatible import-order rules.
 *
 * Both paths are safe to register; the IDE calls this implementation only when
 * `supports(file)` returns true.
 */
class BuffImportOptimizer : ImportOptimizer {

    override fun supports(file: PsiFile): Boolean =
        file.virtualFile?.fileType == BasedPythonFileType.INSTANCE

    override fun processFile(file: PsiFile): Runnable {
        // Capture everything we need before leaving the EDT.
        val project = file.project
        val vf = file.virtualFile
        val path = vf?.toNioPath()

        return Runnable {
            if (path == null) return@Runnable

            // processFile's Runnable is expected to run on a background thread;
            // wrap in a Task so there is visible progress feedback.
            ProgressManager.getInstance().run(
                object : Task.Backgroundable(project, BasedPythonBundle.message("progress.optimizeImports"), false) {
                    override fun run(indicator: ProgressIndicator) {
                        indicator.isIndeterminate = true
                        indicator.text2 = vf.name

                        val out = ByCli.runBuff(
                            project,
                            "check", "--fix", "--select", "I", path.toString(),
                            cwd = path.parent,
                            contextFile = vf,
                            title = "buff organize-imports",
                        ) ?: return

                        if (out.exitCode != 0) {
                            ByCli.notifyError(
                                project,
                                BasedPythonBundle.message("notification.organizeImportsFailed.title"),
                                out.stderr.ifBlank { BasedPythonBundle.message("notification.exitCode", out.exitCode) },
                            )
                        } else {
                            VfsUtil.markDirtyAndRefresh(true, false, false, vf)
                        }
                    }
                }
            )
        }
    }
}
