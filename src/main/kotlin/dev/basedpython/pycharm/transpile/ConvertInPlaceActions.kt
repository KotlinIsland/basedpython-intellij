package dev.basedpython.pycharm.transpile

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.lang.BasedPythonFileType
import java.nio.file.Paths

// ---------------------------------------------------------------------------
// "Convert .by → .py (in place)"
//
// Runs `by transpile <file>` and writes the result to an `out/` sibling at
// <projectRoot>/out/<relPath>.py, creating the file if necessary.
// ---------------------------------------------------------------------------

/**
 * Action: "Convert .by → .py (in place)"
 *
 * Runs `by transpile <currentFile>`, then writes the Python output to the
 * corresponding `out/<relPath>.py` file (creating it if necessary).  Opens
 * the result in the editor.
 */
class ConvertByToPyAction : AnAction() {

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
        val filePath = file.toNioPath()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Converting ${file.name} → .py", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val out = ByCli.run(project, "transpile", filePath.toString(), cwd = filePath.parent) ?: return
                    if (out.exitCode != 0) {
                        ByCli.notifyError(
                            project,
                            "by transpile failed",
                            out.stderr.ifBlank { "exit ${out.exitCode}" },
                        )
                        return
                    }

                    val pyContent = out.stdout
                    val base = Paths.get(basePath)
                    val relPath = try { base.relativize(filePath) } catch (_: IllegalArgumentException) { filePath.fileName }
                    val relStr = relPath.toString().replaceFirst(Regex("\\.by$", RegexOption.IGNORE_CASE), ".py")
                    val outPath = base.resolve("out").resolve(relStr)

                    ApplicationManager.getApplication().invokeLater {
                        WriteCommandAction.runWriteCommandAction(project, "Convert .by → .py", null, {
                            writeTextToPath(project, outPath, pyContent)
                        })
                    }
                }
            },
        )
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}

// ---------------------------------------------------------------------------
// "Convert .py → .by (in place)"
//
// Runs `by transpile --reverse <file>` and writes the result to a `.by` sibling
// in the same directory as the source .py file.
// ---------------------------------------------------------------------------

/**
 * Action: "Convert .py → .by (in place)"
 *
 * Runs `by transpile --reverse <currentFile>` and writes the basedpython output
 * to a `.by` sibling file next to the source `.py`.  Opens the result in the editor.
 */
class ConvertPyToByAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            file != null && !file.isDirectory && isPyFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        val filePath = file.toNioPath()

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Converting ${file.name} → .by", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val out =
                        ByCli.run(project, "transpile", "--reverse", filePath.toString(), cwd = filePath.parent)
                            ?: return
                    if (out.exitCode != 0) {
                        ByCli.notifyError(
                            project,
                            "by transpile --reverse failed",
                            out.stderr.ifBlank { "exit ${out.exitCode}" },
                        )
                        return
                    }

                    val byContent = out.stdout
                    val byPath = filePath.parent.resolve(file.nameWithoutExtension + ".by")

                    ApplicationManager.getApplication().invokeLater {
                        WriteCommandAction.runWriteCommandAction(project, "Convert .py → .by", null, {
                            writeTextToPath(project, byPath, byContent)
                        })
                    }
                }
            },
        )
    }

    private fun isPyFile(file: VirtualFile): Boolean = file.extension.equals("py", ignoreCase = true)
}

// ---------------------------------------------------------------------------
// Shared helper: write text to a (possibly new) path via VFS
// ---------------------------------------------------------------------------

/**
 * Writes [content] to [path] using the IntelliJ VFS so that document listeners fire correctly.
 * Must be called inside a WriteCommandAction / WriteAction.
 */
private fun writeTextToPath(project: Project, path: java.nio.file.Path, content: String) {
    // Ensure parent directories exist on disk
    java.nio.file.Files.createDirectories(path.parent)

    val parentVf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path.parent)
        ?: run {
            // Fall back to direct write if VFS can't find the dir
            java.nio.file.Files.writeString(path, content)
            return
        }

    val fileName = path.fileName.toString()
    val existing = parentVf.findChild(fileName)
    val targetVf: VirtualFile = if (existing != null) {
        existing
    } else {
        parentVf.createChildData(project, fileName)
    }

    VfsUtil.saveText(targetVf, content)

    // Open the newly written file in the editor (still on EDT)
    FileEditorManager.getInstance(project).openFile(targetVf, true)
}
