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
import dev.basedpython.pycharm.lang.BasedPythonFileType
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Action: "Debug .by (pdb)".
 *
 * basedpython transpiles `.by` to Python under `out/`. There is no source-mapped
 * IDE debugger yet (the transpiler's line map is internal and not emitted as a
 * sidecar by the CLI), so this action gives the next best thing without depending
 * on the optional Python plugin: it runs `by build`, then launches the generated
 * `.py` under the standard library debugger (`python -m pdb`) in an interactive
 * console. pdb's `> path(line)` frames are clickable thanks to the basedpython
 * console filter, and "Go to Generated .py" maps frames back to the `.by` source.
 */
class DebugWithPdbAction : AnAction() {

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
            ByCli.notifyError(project, "Debug .by (pdb)", "Could not resolve output path for ${file.name}.")
            return
        }
        val cwd = Paths.get(basePath)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Building for debug…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val out = ByCli.run(project, "build", cwd = cwd) ?: return
                if (out.exitCode != 0) {
                    ByCli.notifyError(project, "by build failed", out.stderr.ifBlank { "exit ${out.exitCode}" })
                    return
                }
                LocalFileSystem.getInstance().refreshAndFindFileByNioFile(outPath.parent ?: cwd)
                    ?.let { VfsUtil.markDirtyAndRefresh(false, true, true, it) }
                if (!Files.exists(outPath)) {
                    ByCli.notifyError(project, "Debug .by (pdb)", "by build succeeded but ${outPath.fileName} was not found in out/.")
                    return
                }
                ApplicationManager.getApplication().invokeLater {
                    launchPdb(project, cwd, outPath, file.nameWithoutExtension)
                }
            }
        })
    }

    private fun launchPdb(project: Project, cwd: Path, outPath: Path, label: String) {
        val python = ByEnvironments.resolvePython(project) ?: run {
            ByCli.notifyError(
                project, "Debug .by (pdb)",
                "No Python interpreter found — create a .venv, configure a Python interpreter, or put python3 on PATH.",
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
            .withTitle("Debug (pdb): $label")
            .withActivateToolWindow(true)
            .withStop({ handler.destroyProcess() }, { !handler.isProcessTerminated })
            .run()
    }


    private fun resolveOutPath(file: VirtualFile, basePath: String): Path? {
        val base = Paths.get(basePath)
        return try {
            val relative = base.relativize(file.toNioPath()).toString()
            val withPy = relative.replaceFirst(Regex("\\.by$", RegexOption.IGNORE_CASE), ".py")
            base.resolve("out").resolve(withPy)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
