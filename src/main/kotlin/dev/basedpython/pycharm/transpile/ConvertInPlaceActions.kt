package dev.basedpython.pycharm.transpile

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.util.BasedPythonBundle
import dev.basedpython.pycharm.lang.BasedPythonFileType
import java.nio.file.Path
import java.nio.file.Paths

// ---------------------------------------------------------------------------
// "Convert .by → .py (in place)"
//
// Asks the `by` server for the file's python and writes it to an `out/` sibling at
// <projectRoot>/out/<relPath>.py, creating the file if necessary.
// ---------------------------------------------------------------------------

/**
 * Action: "Convert .by → .py (in place)"
 *
 * Transpiles the current file through the `by` server, then writes the Python output to the
 * corresponding `out/<relPath>.py` file (creating it if necessary). Opens the result in the editor.
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
                    val pyContent = ByTranspile.sourceOrNotify(
                        project,
                        file,
                        failureTitle = BasedPythonBundle.message("notification.transpileFailed.title"),
                    ) ?: return
                    val base = Paths.get(basePath)
                    val relPath = try { base.relativize(filePath) } catch (_: IllegalArgumentException) { filePath.fileName }
                    val relStr = relPath.toString().replaceFirst(Regex("\\.by$", RegexOption.IGNORE_CASE), ".py")
                    val outPath = base.resolve("out").resolve(relStr)

                    ApplicationManager.getApplication().invokeLater({
                        writeConvertedAndOpen(project, outPath, pyContent, "Convert .by → .py")
                    }, project.disposed)
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
// Asks the `by` server to reverse the file and writes the result to a `.by` sibling in the same
// directory as the source .py file.
// ---------------------------------------------------------------------------

/**
 * Action: "Convert .py → .by (in place)"
 *
 * Reverse-transpiles the current file through the `by` server and writes the basedpython output to
 * a `.by` sibling file next to the source `.py`. Opens the result in the editor.
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
                    val byContent = ByTranspile.sourceOrNotify(
                        project,
                        file,
                        reverse = true,
                        failureTitle = BasedPythonBundle.message("notification.reverseTranspileFailed.title"),
                    ) ?: return
                    val byPath = filePath.parent.resolve(file.nameWithoutExtension + ".by")

                    ApplicationManager.getApplication().invokeLater({
                        writeConvertedAndOpen(project, byPath, byContent, "Convert .py → .by")
                    }, project.disposed)
                }
            },
        )
    }

    private fun isPyFile(file: VirtualFile): Boolean = file.extension.equals("py", ignoreCase = true)
}

// ---------------------------------------------------------------------------
// Shared helper: write the converted source through the VFS, as one undoable command
// ---------------------------------------------------------------------------

/** [writeConvertedSource], asking before it replaces a file that exists, then opening the result. */
private fun writeConvertedAndOpen(project: Project, path: Path, content: String, commandName: String) {
    val written = writeConvertedSource(project, path, content, commandName) { existing ->
        MessageDialogBuilder
            .yesNo(
                BasedPythonBundle.message("convert.overwrite.title", existing.name),
                BasedPythonBundle.message("convert.overwrite.message", existing.presentableUrl),
            )
            .yesText(BasedPythonBundle.message("convert.overwrite.button"))
            .ask(project)
    } ?: return
    FileEditorManager.getInstance(project).openFile(written, true)
}

/**
 * Writes [content] to [path] and returns the file, or null when nothing was written.
 *
 * A target that already exists is only replaced when [confirmOverwrite] says so — it may well be a
 * hand-written file, and a conversion is no reason to lose it without asking.
 *
 * Everything goes through the VFS inside one write command: the missing directories, the file, and
 * the text, which is set on the file's document. So the IDE sees the change as it happens rather
 * than on some later refresh, and the whole conversion is one step of *Undo*. Writing to disk
 * directly, as this used to when the directory was not yet known to the VFS, did neither.
 */
internal fun writeConvertedSource(
    project: Project,
    path: Path,
    content: String,
    commandName: String,
    confirmOverwrite: (VirtualFile) -> Boolean,
): VirtualFile? {
    val existing = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    if (existing != null && !confirmOverwrite(existing)) return null

    return WriteCommandAction.writeCommandAction(project)
        .withName(commandName)
        .compute<VirtualFile?, RuntimeException> {
            val document = existing?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (existing != null && document != null) {
                // Through the document, which is what undo records; saving follows the IDE's own
                // rules, actions on save included.
                document.setText(content)
                FileDocumentManager.getInstance().saveDocument(document)
                return@compute existing
            }
            val target = existing ?: run {
                val parent = VfsUtil.createDirectoryIfMissing(FileUtil.toSystemIndependentName(path.parent.toString()))
                    ?: return@compute null
                parent.createChildData(project, path.fileName.toString())
            }
            // A file created in this command: undoing the command deletes it, content and all.
            VfsUtil.saveText(target, content)
            target
        }
}
