package dev.basedpython.pycharm.transpile

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Action: "Show Transpiled Python"
 *
 * Opens a side-by-side diff: the current .by source on the left, the generated Python (read-only)
 * on the right. While that diff is showing, edits to the source re-transpile it (debounced) and the
 * right-hand side is rewritten in place; closing the diff stops the watching.
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
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Transpiling ${file.name}", true) {
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    val pythonSource = ByTranspile.sourceOrNotify(
                        project,
                        file,
                        failureTitle = BasedPythonBundle.message("notification.transpileFailed.title"),
                    ) ?: return
                    ApplicationManager.getApplication().invokeLater({
                        val request = TranspiledPythonDiff.create(project, file, pythonSource) ?: return@invokeLater
                        DiffManager.getInstance().showDiff(project, request)
                    }, project.disposed)
                }
            },
        )
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}

/**
 * The diff [ShowTranspiledDiffAction] shows, and what keeps its python side current.
 *
 * The python side is one document owned by this request and rewritten in place, so however often
 * the source changes there is one diff on screen, the one the user opened. The previous version
 * called `DiffManager.showDiff` on every refresh — which opens a new diff each time — and watched
 * the source for as long as the project stayed open, so after the diff was closed new ones kept
 * appearing while the user typed.
 *
 * Watching is tied to the diff being shown: the platform tells a request when a viewer takes it on
 * and when it lets it go ([onAssigned]), and the source is watched only while at least one does.
 */
internal class TranspiledPythonDiff private constructor(
    private val project: Project,
    private val byFile: VirtualFile,
    private val byDocument: Document,
    /** The python side; read-only to the user, rewritten by the refresh. */
    val pythonDocument: Document,
    private val transpile: (Project, VirtualFile) -> String?,
    private val debounce: Duration,
) : SimpleDiffRequest(
    "Transpiled: ${byFile.name} ↔ ${byFile.nameWithoutExtension}.py",
    DiffContentFactory.getInstance().create(project, byFile),
    DiffContentFactory.getInstance().create(
        project,
        pythonDocument,
        FileTypeManager.getInstance().getFileTypeByExtension("py"),
    ),
    byFile.name,
    "${byFile.nameWithoutExtension}.py (generated)",
) {

    private var assignments = 0
    private var watching: Watch? = null

    /** Whether edits to the source are currently being followed. */
    val isWatching: Boolean get() = watching != null

    override fun onAssigned(isAssigned: Boolean) {
        super.onAssigned(isAssigned)
        if (isAssigned) {
            if (assignments++ == 0) watching = watch()
        } else if (assignments > 0 && --assignments == 0) {
            watching?.stop()
            watching = null
        }
    }

    private class Watch(val listener: Disposable, val job: Job) {
        fun stop() {
            job.cancel()
            Disposer.dispose(listener)
        }
    }

    @OptIn(FlowPreview::class)
    private fun watch(): Watch {
        val refresh = project.service<TranspiledDiffRefresh>()
        val listener = Disposer.newDisposable(refresh, "transpiled diff of ${byFile.path}")
        val edits = Channel<Unit>(Channel.CONFLATED)
        byDocument.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                edits.trySend(Unit)
            }
        }, listener)

        val job = refresh.scope.launch {
            edits.consumeAsFlow().debounce(debounce).collectLatest {
                // Quiet on failure: this fires while the file is being typed into, and source that
                // does not lower yet is the ordinary state mid-edit rather than something to
                // interrupt anyone about. `collectLatest` drops a transpile the next edit overtook.
                val python = withContext(Dispatchers.Default) {
                    coroutineToIndicator { transpile(project, byFile) }
                } ?: return@collectLatest
                edtWriteAction { replacePython(python) }
            }
        }
        return Watch(listener, job)
    }

    private fun replacePython(python: String) {
        if (pythonDocument.text == python) return
        CommandProcessor.getInstance().runUndoTransparentAction {
            pythonDocument.setReadOnly(false)
            try {
                pythonDocument.setText(python)
            } finally {
                pythonDocument.setReadOnly(true)
            }
        }
    }

    companion object {
        private val REFRESH_DEBOUNCE = 500.milliseconds

        /** A diff of [byFile] against [python], or null when the file has no document to watch. */
        fun create(
            project: Project,
            byFile: VirtualFile,
            python: String,
            transpile: (Project, VirtualFile) -> String? = { p, f ->
                (ByTranspile.toPython(p, f) as? ByTranspileResult.Generated)?.source
            },
            debounce: Duration = REFRESH_DEBOUNCE,
        ): TranspiledPythonDiff? {
            val byDocument = FileDocumentManager.getInstance().getDocument(byFile) ?: return null
            val pythonDocument = EditorFactory.getInstance().createDocument(python).apply { setReadOnly(true) }
            return TranspiledPythonDiff(project, byFile, byDocument, pythonDocument, transpile, debounce)
        }
    }
}

/**
 * What a [TranspiledPythonDiff]'s refresh hangs off: the scope its transpiles run in, and the parent
 * its document listener is disposed with.
 *
 * A document outlives the project that opened it, so a listener added with no parent stays on it for
 * as long as the IDE runs, holding the project and this plugin's classloader after either was closed
 * or unloaded. Closing the diff removes the listener; this service is the backstop for a diff still
 * open when the project closes or the plugin unloads.
 */
@Service(Service.Level.PROJECT)
internal class TranspiledDiffRefresh(val scope: CoroutineScope) : Disposable {
    override fun dispose() = Unit
}
