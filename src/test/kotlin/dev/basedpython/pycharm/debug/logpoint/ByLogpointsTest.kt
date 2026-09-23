package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.impl.UndoManagerImpl
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.debug.ByBreakpointProperties
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What counts as a log point, and whether one can be taken back.
 *
 * Both answers used to be "only if this plugin made it". *Add Logging Breakpoint…* — the platform's
 * own entry in the gutter menu — makes an ordinary breakpoint and then turns logging on and
 * suspending off, so it produced something that logs instead of stopping, shows no `Log:` box, and
 * could not be undone.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByLogpointsTest {

    private val fixture by codeInsightFixture()

    private val breakpoints get() = XDebuggerManager.getInstance(fixture.project).breakpointManager

    private val type get() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    /** A breakpoint made the way the platform's own gutter menu makes one: plain, then edited. */
    private fun gutterLoggingBreakpoint(): XLineBreakpoint<*> {
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val breakpoint = breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 1, ByBreakpointProperties())
        breakpoint.suspendPolicy = SuspendPolicy.NONE
        breakpoint.logExpressionObject = ByLogpoints.expressionOf("x")
        return breakpoint
    }

    /**
     * Whether Ctrl+Z would take the log point back — asked by name.
     *
     * `isUndoAvailable` cannot answer it: the fixture writes the file to configure it, so there is
     * always a document edit underneath to undo, and the question here is what is on *top*.
     */
    private fun topUndoIsTheLogPoint(editor: TextEditor): Boolean =
        UndoManager.getInstance(fixture.project).isUndoAvailable(editor) &&
            UndoManager.getInstance(fixture.project).getUndoActionNameAndDescription(editor).first
                .contains("Add Log Point")

    @Test
    fun `a breakpoint that logs instead of suspending is a log point, whoever made it`() {
        val breakpoint = gutterLoggingBreakpoint()

        assertNotNull(
            ByLogpoints.asLogpoint(breakpoint),
            "the gutter menu sets no property of this plugin's, so the behaviour has to be enough",
        )
    }

    @Test
    fun `a breakpoint that still suspends is not a log point`() {
        val breakpoint = gutterLoggingBreakpoint()
        breakpoint.suspendPolicy = SuspendPolicy.ALL

        assertNull(
            ByLogpoints.asLogpoint(breakpoint),
            "an ordinary breakpoint that also logs stops on its line, and does not belong in a gap",
        )
    }

    @Test
    fun `a breakpoint that logs nothing is not a log point`() {
        val breakpoint = gutterLoggingBreakpoint()
        breakpoint.logExpressionObject = null

        assertNull(ByLogpoints.asLogpoint(breakpoint))
    }

    @Test
    fun `a temporary breakpoint is not a log point`() {
        val breakpoint = gutterLoggingBreakpoint()
        breakpoint.isTemporary = true

        assertNull(ByLogpoints.asLogpoint(breakpoint), "one that removes itself on the first hit logs once")
    }

    @Test
    fun `an empty log point this plugin made is still a log point`() {
        // Add Log Point makes one with nothing to log yet. It is a log point the moment it exists,
        // which is what the property carries and behaviour cannot.
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val breakpoint = breakpoints.addLineBreakpoint(
            type,
            fixture.file.virtualFile.url,
            1,
            ByLogpoints.logpointProperties(),
        )

        assertNotNull(ByLogpoints.asLogpoint(breakpoint))
    }

    @Test
    fun `the gutter route makes a log point in two steps, and the pair is one undo`() {
        // Add Logging Breakpoint… adds a plain breakpoint and then edits it, so the log point comes
        // into existence as a *change*. Undo has to join the two or the gesture cannot be taken back.
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)
        val undo = UndoManager.getInstance(fixture.project)
        val listener = ByLogpointFields(fixture.project)

        val breakpoint = breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 1, ByBreakpointProperties())
        listener.breakpointAdded(breakpoint)
        assertFalse(topUndoIsTheLogPoint(editor), "a plain breakpoint is not a log point and not an undo step")

        breakpoint.suspendPolicy = SuspendPolicy.NONE
        breakpoint.logExpressionObject = ByLogpoints.expressionOf("x")
        listener.breakpointChanged(breakpoint)

        assertTrue(topUndoIsTheLogPoint(editor), "the breakpoint became a log point, and that is undoable")
        undo.undo(editor)
        assertTrue(breakpoints.getBreakpoints(type).none { ByLogpoints.asLogpoint(it) != null })
    }

    @Test
    fun `a breakpoint converted long after it was made is not undone away`() {
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)
        val listener = ByLogpointFields(fixture.project)

        val old = breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 1, ByBreakpointProperties())
        listener.breakpointAdded(old)
        // Anything else added since means the first one is no longer the one being made.
        val newer = breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 0, ByBreakpointProperties())
        listener.breakpointAdded(newer)

        old.suspendPolicy = SuspendPolicy.NONE
        old.logExpressionObject = ByLogpoints.expressionOf("x")
        listener.breakpointChanged(old)

        assertFalse(
            topUndoIsTheLogPoint(editor),
            "turning an existing breakpoint into a log point is an edit, not a creation to undo",
        )
    }

    @Test
    fun `a log point created outside a command can still be undone`() {
        // Every gutter route is outside a command — the platform's Add Logging Breakpoint… and
        // IntelliJ IDEA's click in the gutter gap both are — so there was no undo step to join and
        // Ctrl+Z did nothing.
        val breakpoint = gutterLoggingBreakpoint()
        val file = fixture.file.virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)

        ByLogpointUndo.record(fixture.project, document, breakpoint)
        assertTrue(topUndoIsTheLogPoint(editor), "adding a log point has to leave something to undo")
        UndoManager.getInstance(fixture.project).undo(editor)

        assertTrue(
            breakpoints.getBreakpoints(type).none { ByLogpoints.asLogpoint(it) != null },
            "undo takes the log point back",
        )
    }

    @Test
    fun `undo and redo put back the same log point, once`() {
        val breakpoint = gutterLoggingBreakpoint()
        val document = FileDocumentManager.getInstance().getDocument(fixture.file.virtualFile)!!
        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)
        val undo = UndoManager.getInstance(fixture.project)

        ByLogpointUndo.record(fixture.project, document, breakpoint)
        undo.undo(editor)
        undo.redo(editor)

        val restored = breakpoints.getBreakpoints(type).mapNotNull { ByLogpoints.asLogpoint(it) }
        assertEquals(1, restored.size, "redo puts back one log point: $restored")
        assertEquals(1, restored.single().line)
        assertEquals("x", restored.single().logExpressionObject?.expression)
        assertEquals(SuspendPolicy.NONE, restored.single().suspendPolicy)
    }

    /**
     * What `UndoManager` keeps for a log point is the platform's own object, holding the platform's
     * and the JDK's: it outlives the plugin — the history lasts as long as the document's does — so
     * one class of this plugin's in it is what kept the plugin from unloading.
     */
    @Test
    fun `the undo step a log point records holds nothing of this plugin's`() {
        val breakpoint = gutterLoggingBreakpoint()
        val document = FileDocumentManager.getInstance().getDocument(fixture.file.virtualFile)!!
        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)

        ByLogpointUndo.record(fixture.project, document, breakpoint)

        // The dump names each recorded action by its class; it named ByLogpointUndo's own before.
        val dump = (UndoManager.getInstance(fixture.project) as UndoManagerImpl).dumpState(editor, "")
        assertTrue("ModCommandExecutorImpl" in dump, "the step is the platform's option change:\n$dump")
        assertFalse("ByLogpoint" in dump, "no action of this plugin's is on the stack:\n$dump")

        // And what that action keeps of ours is the option's value: JDK types only.
        val kept = ByLogpointOptions.describe(breakpoint)
        val types = (listOf(kept) + kept).map { it.javaClass.name }
        assertTrue(types.all { it.startsWith("java.") }, "the undo step holds $types")
    }

    /**
     * A log point reported on another thread while the EDT is in the middle of a command — a
     * keystroke, here — gets an undo step of its own after it, and is not folded into the keystroke.
     * Asked from that thread, "is a command open" was answering about the keystroke, and Ctrl+Z
     * then took back the typing and the log point together.
     */
    @Test
    fun `a log point reported off the EDT does not join the command the EDT is in`() {
        fixture.configureByText("main.by", "def f(x):\n    return x\n")
        val editor: TextEditor = TextEditorProvider.getInstance().getTextEditor(fixture.editor)
        val document = fixture.editor.document
        val listener = ByLogpointFields(fixture.project)

        // Made so that nothing has recorded an undo for it yet: a plain breakpoint that a newer one
        // displaced before it became a log point, which is no creation to undo on its own.
        val breakpoint = breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 1, ByBreakpointProperties())
        breakpoints.addLineBreakpoint(type, fixture.file.virtualFile.url, 0, ByBreakpointProperties())
        breakpoint.suspendPolicy = SuspendPolicy.NONE
        breakpoint.logExpressionObject = ByLogpoints.expressionOf("x")
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        CommandProcessor.getInstance().executeCommand(
            fixture.project,
            {
                WriteAction.run<RuntimeException> { document.insertString(0, "#") }
                ApplicationManager.getApplication().executeOnPooledThread { listener.breakpointAdded(breakpoint) }.get()
            },
            "Typing",
            null,
            document,
        )
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertTrue(topUndoIsTheLogPoint(editor), "the log point has an undo step of its own")
        UndoManager.getInstance(fixture.project).undo(editor)
        assertTrue(breakpoints.getBreakpoints(type).none { ByLogpoints.asLogpoint(it) != null }, "undo takes the log point back")
        assertTrue(document.text.startsWith("#"), "undoing the log point took the keystroke with it: ${document.text}")
    }
}
