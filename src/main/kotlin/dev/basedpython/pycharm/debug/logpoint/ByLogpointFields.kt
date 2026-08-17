package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointListener
import dev.basedpython.pycharm.debug.ByLineBreakpointType

/**
 * Keeps a [ByLogpointField] on screen for every `.by` log point, for as long as it exists.
 *
 * The field is how a log point looks, not a prompt that opens once — so it has to arrive by every
 * route a log point does. A click in the gutter gap and `Ctrl+Alt+F8` both go through the breakpoint
 * manager, so [breakpointAdded] catches them and the `print` quick fix alike; a file opened with log
 * points already in it never fires that at all, which is what [ByLogpointFieldsOnEditorOpen] is for.
 */
class ByLogpointFields(private val project: Project) : XBreakpointListener<XBreakpoint<*>> {

    override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
        val logpoint = ByLogpoints.asLogpoint(breakpoint) ?: return
        if (!ByLogpoints.pluginProvidesLogpointUi()) return

        // Joins the command the gutter click opened, so Ctrl+Z takes the log point back the way it
        // does for the IDE's own. A no-op outside a command, which is how log points restored from
        // the workspace at startup avoid becoming undo steps.
        val file = logpoint.sourcePosition?.file ?: return
        // In a read action: breakpoints are added from a coroutine dispatcher as well as from the
        // EDT, and looking a document up off both is a read-access assertion, not a race to lose.
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getDocument(file)
        }
        document?.let { ByLogpointUndo.record(project, it, logpoint) }

        // Deferred: the breakpoint's own gutter highlighter is installed as part of adding it, and
        // placing an inlay from inside that notification would be reentrant.
        onEdt {
            if (project.isDisposed) return@onEdt
            editorsFor(file).forEach { ByLogpointField.show(project, it, logpoint) }
        }
    }

    /**
     * Takes the box away with the log point — on the EDT, whatever thread the news arrives on.
     *
     * `FrontendXBreakpointManager` removes breakpoints from a coroutine dispatcher, and disposing an
     * inlay off the EDT is an assertion failure rather than a race you get away with.
     */
    override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
        val logpoint = ByLogpoints.asLogpoint(breakpoint) ?: return
        val file = logpoint.sourcePosition?.file ?: return
        onEdt { editorsFor(file).forEach { ByLogpointField.of(it, logpoint)?.close() } }
    }

    /**
     * Reopens the field on a log point whose expression changed elsewhere — the breakpoint dialog,
     * or an undo. Editing in the field itself is already in step, so the cheap guard is enough.
     */
    override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
        val logpoint = ByLogpoints.asLogpoint(breakpoint) ?: return
        if (!ByLogpoints.pluginProvidesLogpointUi()) return
        val file = logpoint.sourcePosition?.file ?: return
        onEdt { editorsFor(file).forEach { ByLogpointField.of(it, logpoint)?.revert() } }
    }

    /** Runs [action] on the EDT, immediately if that is already where we are. */
    private fun onEdt(action: () -> Unit) {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeLater(action, project.disposed)
    }

    private fun editorsFor(file: VirtualFile): List<EditorEx> =
        com.intellij.openapi.editor.EditorFactory.getInstance().allEditors
            .filterIsInstance<EditorEx>()
            .filter { it.project == project && FileDocumentManager.getInstance().getFile(it.document) == file }

    companion object {
        /** Shows fields for every log point already set in [editor]'s file. */
        fun populate(project: Project, editor: EditorEx) {
            if (!ByLogpoints.pluginProvidesLogpointUi()) return
            val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
            if (!file.extension.equals("by", ignoreCase = true)) return
            val type = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java) ?: return
            XDebuggerManager.getInstance(project).breakpointManager
                .getBreakpoints(type)
                .filter { it.sourcePosition?.file == file }
                .mapNotNull { ByLogpoints.asLogpoint(it) }
                .forEach { ByLogpointField.show(project, editor, it) }
        }
    }
}

/**
 * Puts the boxes back when a `.by` file is opened with log points already in it.
 *
 * Breakpoints outlive editors — they are workspace state — so a log point set in a previous session,
 * or in a tab that was closed and reopened, would otherwise be a gutter icon with nothing beside it
 * saying what it logs.
 *
 * On `FileEditorManagerListener` rather than `EditorFactoryListener`, which was the first attempt and
 * silently did nothing: declarative listener registration resolves the `Topic` declared on the
 * listener interface, and `EditorFactoryListener` has none. The registration was accepted, no
 * listener was ever attached, and the symptom was exactly the bug it was written to fix.
 */
class ByLogpointFieldsOnFileOpen(private val project: Project) : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!file.extension.equals("by", ignoreCase = true)) return
        // After the editor is built and its inlay model is in place.
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            source.getAllEditors(file)
                .filterIsInstance<TextEditor>()
                .mapNotNull { it.editor as? EditorEx }
                .forEach { ByLogpointFields.populate(project, it) }
        }, project.disposed)
    }
}
