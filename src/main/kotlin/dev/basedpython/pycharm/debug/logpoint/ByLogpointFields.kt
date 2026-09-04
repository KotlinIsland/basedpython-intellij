package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.startup.StartupManager
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
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import java.lang.ref.WeakReference

/**
 * Keeps a [ByLogpointField] on screen for every `.by` log point, for as long as it exists.
 *
 * The field is how a log point looks, not a prompt that opens once — so it has to arrive by every
 * route a log point does. A click in the gutter gap and `Ctrl+Alt+F8` both go through the breakpoint
 * manager, so [breakpointAdded] catches them and the `print` quick fix alike; a file opened with log
 * points already in it never fires that at all, which is what [ByLogpointFieldsOnEditorOpen] is for.
 */
class ByLogpointFields(private val project: Project) : XBreakpointListener<XBreakpoint<*>> {

    /**
     * The `.by` breakpoint added most recently that was not a log point yet — see [breakpointChanged].
     *
     * Weak, because a breakpoint the user removes a moment later should not be held alive by a field
     * remembering it. Volatile, because breakpoints are added from a coroutine dispatcher as well as
     * from the EDT.
     */
    @Volatile
    private var justAdded: WeakReference<XLineBreakpoint<*>>? = null

    override fun breakpointAdded(breakpoint: XBreakpoint<*>) {
        val line = breakpoint as? XLineBreakpoint<*> ?: return
        if (line.type !is ByLineBreakpointType) return
        val logpoint = ByLogpoints.asLogpoint(breakpoint)
        // Remembered whether or not it is a log point yet, so the next add always displaces the
        // last: whatever the gesture turns out to be, only the newest breakpoint is still being made.
        justAdded = if (logpoint == null) WeakReference(line) else null
        if (logpoint == null) return
        val file = logpoint.sourcePosition?.file ?: return

        // Undo first, and in every IDE — unlike the box below, which stands down where the IDE draws
        // its own. There is no breakpoint undo anywhere in the platform, IntelliJ IDEA's log points
        // included, so this is the only thing that makes Ctrl+Z take one back there either.
        recordUndo(logpoint, file)
        if (!ByLogpoints.pluginProvidesLogpointUi()) return

        // Deferred: the breakpoint's own gutter highlighter is installed as part of adding it, and
        // placing an inlay from inside that notification would be reentrant.
        onEdt {
            if (project.isDisposed) return@onEdt
            editorsFor(file).forEach { ByLogpointField.show(project, it, logpoint) }
        }
    }

    /**
     * Makes [logpoint] undoable, if it is one a person just created rather than one being restored.
     *
     * The two are told apart by whether the file's document is already **loaded**: a log point put
     * there by hand is put there in an editor, and a log point read out of `workspace.xml` while the
     * project opens is read before anything has opened its file. Hence `getCachedDocument` rather
     * than `getDocument`, which would answer for both — and would drag every `.by` file holding a
     * breakpoint into memory at startup to do it. The startup check is the same statement made
     * twice, for the case where a document does happen to be loaded early.
     */
    private fun recordUndo(logpoint: XLineBreakpoint<*>, file: VirtualFile) {
        if (!StartupManager.getInstance(project).postStartupActivityPassed()) return
        // In a read action: breakpoints are added from a coroutine dispatcher as well as from the
        // EDT, and looking a document up off both is a read-access assertion, not a race to lose.
        val document = ReadAction.compute<Document?, RuntimeException> {
            FileDocumentManager.getInstance().getCachedDocument(file)
        } ?: return
        // A command joined has to be joined now, while it is still open; a command of our own has to
        // be opened on the EDT, which this is not always on.
        if (CommandProcessor.getInstance().currentCommand != null) {
            ByLogpointUndo.record(project, document, logpoint)
        } else {
            onEdt { if (!project.isDisposed) ByLogpointUndo.record(project, document, logpoint) }
        }
    }

    /**
     * Takes the box away with the log point — on the EDT, whatever thread the news arrives on.
     *
     * `FrontendXBreakpointManager` removes breakpoints from a coroutine dispatcher, and disposing an
     * inlay off the EDT is an assertion failure rather than a race you get away with.
     */
    override fun breakpointRemoved(breakpoint: XBreakpoint<*>) {
        // Asked of the breakpoint rather than of [ByLogpoints.asLogpoint]: one that stopped being a
        // log point a moment before it was removed still has a box open, and a box whose breakpoint
        // is gone is a text field committing to nothing.
        val line = breakpoint as? XLineBreakpoint<*> ?: return
        if (justAdded?.get() === line) justAdded = null
        val file = line.sourcePosition?.file ?: return
        onEdt { editorsFor(file).forEach { ByLogpointField.of(it, line)?.close() } }
    }

    /**
     * Follows a breakpoint that was edited somewhere else — the breakpoint dialog or balloon, or an
     * undo.
     *
     * Three outcomes, because whether a breakpoint *is* a log point is something an edit can change.
     * A log point whose expression moved on gets the new text put back in its box. One that has just
     * become a log point gets a box: that is *Add Logging Breakpoint…* from the gutter, which makes
     * an ordinary breakpoint and only then turns logging on and suspending off, so the moment it
     * becomes a log point is a change and never an addition. And one that has stopped being a log
     * point — suspending turned back on, logging turned off — loses its box, which is the same
     * statement read backwards.
     */
    override fun breakpointChanged(breakpoint: XBreakpoint<*>) {
        val line = breakpoint as? XLineBreakpoint<*> ?: return
        if (line.type !is ByLineBreakpointType) return
        val file = line.sourcePosition?.file ?: return
        val logpoint = ByLogpoints.asLogpoint(breakpoint)

        // The other half of the undo in [breakpointAdded], for the gesture that arrives in two
        // steps. *Add Logging Breakpoint…* makes an ordinary breakpoint and only then turns logging
        // on and suspending off, so the log point comes into existence as a change; joining the two
        // is the only way Ctrl+Z can take it back. Only the breakpoint that is still the newest
        // qualifies, and only once — a breakpoint someone made a while ago and converts today is a
        // change to a breakpoint they already had, not a log point being created.
        if (logpoint != null && justAdded?.get() === line) {
            justAdded = null
            recordUndo(logpoint, file)
        }

        if (!ByLogpoints.pluginProvidesLogpointUi()) return
        onEdt {
            if (project.isDisposed) return@onEdt
            editorsFor(file).forEach { editor ->
                val open = ByLogpointField.of(editor, line)
                when {
                    logpoint == null -> open?.close()
                    open != null -> open.revert()
                    else -> ByLogpointField.show(project, editor, logpoint)
                }
            }
        }
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
            if (!BasedPythonSources.isOwnedSource(file)) return
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
        if (!BasedPythonSources.isOwnedSource(file)) return
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
