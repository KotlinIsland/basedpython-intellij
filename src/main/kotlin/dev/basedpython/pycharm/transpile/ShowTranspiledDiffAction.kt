package dev.basedpython.pycharm.transpile

import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import dev.basedpython.pycharm.actions.ByCli
import dev.basedpython.pycharm.util.BasedPythonBundle
import dev.basedpython.pycharm.lang.BasedPythonFileType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

/**
 * Action: "Show Transpiled Python"
 *
 * Opens a side-by-side diff: the current .by source on the left, the generated Python (read-only)
 * on the right.  Also installs a debounced document listener (500 ms) that refreshes the right-hand
 * side automatically while the file is open.
 */
class ShowTranspiledDiffAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible =
            file != null && !file.isDirectory && isByFile(file)
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        runTranspileAndShowDiff(project, file)
    }

    private fun runTranspileAndShowDiff(project: Project, file: VirtualFile) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Transpiling ${file.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val pythonSource = ByTranspile.sourceOrNotify(
                        project,
                        file,
                        failureTitle = BasedPythonBundle.message("notification.transpileFailed.title"),
                    ) ?: return
                    ApplicationManager.getApplication().invokeLater {
                        showDiff(project, file, pythonSource)
                        installDocumentListener(project, file)
                    }
                }
            },
        )
    }

    private fun showDiff(project: Project, byFile: VirtualFile, pythonSource: String) {
        val pyFileType = FileTypeManager.getInstance().getFileTypeByExtension("py")
        val rightVf = LightVirtualFile(
            byFile.nameWithoutExtension + ".py",
            pyFileType,
            pythonSource,
        ).also { it.isWritable = false }

        val factory = com.intellij.diff.DiffContentFactory.getInstance()

        // Left: live document for the .by source
        val leftContent = factory.create(project, byFile)
        // Right: in-memory read-only Python
        val rightContent = factory.create(project, rightVf)

        val request = SimpleDiffRequest(
            "Transpiled: ${byFile.name} ↔ ${rightVf.name}",
            leftContent,
            rightContent,
            byFile.name,
            "${rightVf.name} (generated)",
        )

        DiffManager.getInstance().showDiff(project, request)
    }

    // ---- live refresh ----------------------------------------------------------

    /** Tracks which files already have a listener installed (per project lifetime). */
    private val listenerInstalled = mutableSetOf<String>()

    private fun installDocumentListener(project: Project, file: VirtualFile) {
        val key = "${project.locationHash}::${file.path}"
        if (!listenerInstalled.add(key)) return   // already watching this file

        val document: Document =
            FileDocumentManager.getInstance().getDocument(file) ?: return

        val pending = AtomicBoolean(false)
        val runningRef = AtomicReference<Task.Backgroundable?>(null)
        val debounce = Timer(500) { _ ->
            if (!pending.compareAndSet(true, false)) return@Timer
            if (project.isDisposed) return@Timer

            // Guard: do not start a new run while one is already in flight
            if (runningRef.get() != null) {
                pending.set(true)   // retry on the next timer tick
                return@Timer
            }

            val task = object : Task.Backgroundable(project, "Refreshing transpile…", false) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.isIndeterminate = true
                        // Quiet on failure: this fires while the file is being typed into, and
                        // source that does not lower yet is the ordinary state mid-edit rather
                        // than something to interrupt anyone about.
                        val pythonSource =
                            (ByTranspile.toPython(project, file) as? ByTranspileResult.Generated)
                                ?.source ?: return
                        ApplicationManager.getApplication().invokeLater {
                            showDiff(project, file, pythonSource)
                        }
                    } finally {
                        runningRef.set(null)
                        if (pending.get()) pending.set(true)   // will retrigger on next tick
                    }
                }
            }
            runningRef.set(task)
            ProgressManager.getInstance().run(task)
        }
        debounce.isRepeats = false

        document.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                pending.set(true)
                debounce.restart()
            }
        })
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)

    companion object {
        private val LOG = Logger.getInstance(ShowTranspiledDiffAction::class.java)
    }
}
