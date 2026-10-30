package dev.basedpython.pycharm.transpile

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.env.ByEnvironments
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Action: "Debug .by (pdb)".
 *
 * The fallback for when the real debugger cannot run. Source-mapped IDE debugging of `.by` files
 * now exists — Debug a `by run` configuration; see [dev.basedpython.pycharm.debug.ByDebugAdapter]
 * and docs/debugging.md — but it needs `debugpy` installed in the interpreter `by run` chooses.
 * This action needs nothing beyond the standard library: it runs `by build`, then launches the
 * generated `.py` under `python -m pdb` in an interactive console. pdb's `> path(line)` frames are
 * clickable thanks to the basedpython console filter, and "Go to Generated .py" maps frames back to
 * the `.by` source.
 *
 * It cannot be source-mapped itself: pdb reports lines of the generated `.py`, and knows nothing of
 * the `_by_sourcemap.py` the build writes beside it.
 */
class DebugWithPdbAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            e.project != null && file != null && !file.isDirectory && file.isInLocalFileSystem && isByFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, BasedPythonBundle.message("transpile.pdb.progress"), true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Where the build writes this file, and where it has to run to write there: `by`'s
                // answer, see ByBuildOutputs.
                val answer = ByBuildOutputs.getInstance(project).of(file)
                val generated = answer?.generated?.let { Paths.get(it) }
                val cwd = answer?.projectRoot?.let { Paths.get(it) }
                if (generated == null || cwd == null) {
                    ByCli.notifyError(
                        project,
                        BasedPythonBundle.message("transpile.pdb.title"),
                        BasedPythonBundle.message("transpile.noBuildOutput", file.name),
                    )
                    return
                }
                val out = buildAt(project, cwd, BasedPythonBundle.message("transpile.pdb.progress")) ?: return
                if (out.exitCode != 0) {
                    ByCli.notifyError(
                        project,
                        BasedPythonBundle.message("transpile.buildFailed.title"),
                        out.stderr.ifBlank { BasedPythonBundle.message("notification.exitCode", out.exitCode) },
                    )
                    return
                }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(generated.parent ?: cwd)
                    ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
                if (!Files.exists(generated)) {
                    ByCli.notifyError(project, BasedPythonBundle.message("transpile.pdb.title"), BasedPythonBundle.message("transpile.buildDidNotWrite", generated))
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    launchPdb(project, cwd, generated, file.nameWithoutExtension)
                }
            }
        })
    }

    private fun launchPdb(project: Project, cwd: Path, outPath: Path, label: String) {
        val python = ByEnvironments.resolvePython(project) ?: run {
            ByCli.notifyError(
                project, BasedPythonBundle.message("transpile.pdb.title"),
                BasedPythonBundle.message("transpile.pdb.noPython"),
            )
            return
        }
        val cmd = GeneralCommandLine(python.exe.toString())
            .withParameters("-m", "pdb", outPath.toString())
            .withWorkDirectory(cwd.toFile())
            .withCharset(Charsets.UTF_8)
            .withEnvironment(python.env)
        val handler = OSProcessHandler(cmd)
        RunContentExecutor(project, handler)
            .withTitle(BasedPythonBundle.message("transpile.pdb.console", label))
            .withActivateToolWindow(true)
            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
            .run()
    }


    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
