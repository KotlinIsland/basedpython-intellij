package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.impl.ui.XDebuggerExpressionEditor
import dev.basedpython.pycharm.debug.ByDebuggerEditorsProvider
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import java.awt.BorderLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The field you type a log point's expression into, sitting in the gap where the log point is.
 *
 * The IDE's own version of this — `XLogpointEditor` and the forty-odd classes around it — is inside
 * IntelliJ IDEA's Java plugin, so outside IDEA a log point could be created from the gutter and then
 * only filled in through the breakpoint popup, which is a strange way to finish a gesture that
 * started in the editor. This is the small version of it: an expression editor in a block inlay, on
 * the platform APIs every IDE has.
 *
 * The inlay goes *above* the breakpoint's line, which is the gap the log point was placed in — an
 * inter-line breakpoint is anchored to the line below its gap.
 *
 * Committing is deliberately generous: Enter, or clicking away. Escape abandons. A log point that
 * never got an expression is removed rather than left behind, because an empty one does nothing at
 * all and its only trace is an icon in the gutter.
 */
class ByLogpointInlineEditor private constructor(
    private val project: Project,
    private val breakpoint: XLineBreakpoint<*>,
    internal val expressionEditor: XDebuggerExpressionEditor,
) : Disposable {

    private var inlay: Inlay<*>? = null
    private var panel: JPanel? = null
    private var closed = false

    /**
     * Whether the field has ever held focus.
     *
     * Opening one is a race it usually loses: the click that creates the log point is a gutter click,
     * and the editor takes focus back as that click finishes. The resulting focus-lost arrives before
     * anyone has typed anything, and committing nothing removes the log point — so the field appeared
     * for a frame and then took itself away. A focus-lost before the first focus-gained is that
     * churn, not the user leaving.
     */
    private var everFocused = false

    /** True while the log point had no expression when this opened — the state that makes Escape delete it. */
    private val startedEmpty = ByLogpoints.isUnfilled(breakpoint)

    override fun dispose() {
        inlay?.let { Disposer.dispose(it) }
        inlay = null
    }

    private fun typedExpression(): String = expressionEditor.expression?.expression?.trim().orEmpty()

    /** The field has focus, so the next time it loses focus the user really is leaving. */
    internal fun focusGained() {
        everFocused = true
    }

    /**
     * Leaving the field saves what is in it. It can never take the log point away.
     *
     * That restriction is the whole point. Opening one is a focus fight — the field asks for focus,
     * gets it, and the editor takes it straight back as the gutter click finishes — so a focus-lost
     * arrives with nothing typed, moments after opening. Treating that as "the user abandoned it"
     * and removing the log point is what made the thing appear for a frame and vanish; gating on
     * whether the field had ever been focused did not help, because it *had* been, a frame earlier.
     *
     * So an empty field losing focus means nothing happened yet, and nothing should. Removing an
     * unfilled log point stays where it belongs: on Escape, and on Enter, both of which are someone
     * saying so. Split out from the listener because `EditorTextField` forwards focus listeners to
     * the editor it wraps, which puts them out of reach of a test with no window to focus things in.
     */
    internal fun focusLost(movedWithinTheField: Boolean) {
        if (!everFocused || movedWithinTheField) return
        if (typedExpression().isEmpty()) return
        commit()
    }

    /** Writes what was typed onto the breakpoint, or removes it if nothing was. */
    fun commit() {
        if (closed) return
        val typed = typedExpression()
        if (typed.isEmpty()) {
            cancel()
            return
        }
        breakpoint.logExpressionObject = XDebuggerUtil.getInstance()
            .createExpression(typed, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)
        close()
    }

    /** Leaves the breakpoint as it was, and takes it away entirely if it never said anything. */
    fun cancel() {
        if (closed) return
        if (startedEmpty) {
            // With a stack, because "the log point appeared for a frame and vanished" has more than
            // one possible author and the difference between them is the whole diagnosis.
            LOG.info("log point abandoned empty, removing it", Throwable("removed here"))
            XDebuggerManager.getInstance(project).breakpointManager.removeBreakpoint(breakpoint)
        }
        close()
    }

    private fun close() {
        if (closed) return
        closed = true
        if (breakpoint.getUserData(OPEN) === this) breakpoint.putUserData(OPEN, null)
        Disposer.dispose(this)
    }

    companion object {

        /**
         * Opens the field over [breakpoint] in [editor], or returns null when there is nowhere to put
         * it — a line the document no longer has, or an editor that cannot host components.
         */
        fun show(project: Project, editor: EditorEx, breakpoint: XLineBreakpoint<*>): ByLogpointInlineEditor? {
            // One field per log point. Asking for it twice — the shortcut pressed on one that is
            // already open — focuses the one that is there rather than stacking another over it.
            breakpoint.getUserData(OPEN)?.let { open ->
                open.expressionEditor.preferredFocusedComponent?.requestFocusInWindow()
                return open
            }

            val document = editor.document
            if (breakpoint.line !in 0 until document.lineCount) return null

            val existing = XDebuggerUtil.getInstance()
                .createExpression(
                    breakpoint.logExpressionObject?.expression.orEmpty(),
                    BasedPythonLanguage,
                    null,
                    EvaluationMode.EXPRESSION,
                )
            val expressionEditor = XDebuggerExpressionEditor(
                project,
                ByDebuggerEditorsProvider,
                HISTORY_ID,
                breakpoint.sourcePosition,
                existing,
                /* multiline = */ false,
                /* editorFont = */ true,
                /* showEditor = */ true,
            )

            val prompt = ByLogpointInlineEditor(project, breakpoint, expressionEditor)
            val panel = prompt.buildPanel()
            prompt.panel = panel

            // showAbove: the gap the log point lives in is the one above the line it is anchored to.
            val properties = EditorEmbeddedComponentManager.Properties(
                EditorEmbeddedComponentManager.ResizePolicy.none(),
                null,
                /* relatesToPrecedingText = */ false,
                /* showAbove = */ true,
                /* showWhenFolded = */ false,
                /* fullWidth = */ false,
                /* priority = */ 0,
                document.getLineStartOffset(breakpoint.line),
            )
            val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editor, panel, properties)
                ?: return null

            prompt.inlay = inlay
            breakpoint.putUserData(OPEN, prompt)
            Disposer.register(inlay) { prompt.close() }
            // After the click that opened this has finished being delivered, so the field is asking
            // for focus once the editor has stopped taking it back.
            ApplicationManager.getApplication().invokeLater {
                if (!prompt.closed) expressionEditor.preferredFocusedComponent?.requestFocusInWindow()
            }
            return prompt
        }

        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(ByLogpointInlineEditor::class.java)
        private val OPEN = Key.create<ByLogpointInlineEditor>("basedpython.logpoint.openEditor")
        private const val HISTORY_ID = "basedpython-logpoint"
        private const val LABEL = "Log:"
    }

    private fun buildPanel(): JPanel {
        val label = JBLabel(LABEL).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyRight(6)
        }
        val panel = object : JPanel(BorderLayout()) {
            // The inlay is disposed with the editor; releasing the expression editor's own editor is
            // EditorTextField's business, and it does it when the component leaves the hierarchy.
            override fun requestFocus() {
                expressionEditor.preferredFocusedComponent?.requestFocus()
            }
        }
        panel.border = JBUI.Borders.empty(2, 6)
        panel.isOpaque = false
        panel.add(label, BorderLayout.WEST)
        panel.add(expressionEditor.component, BorderLayout.CENTER)

        val field = expressionEditor.editorComponent
        DumbAwareAction.create { commit() }.registerCustomShortcutSet(CommonShortcuts.ENTER, field, this)
        DumbAwareAction.create { cancel() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, field, this)
        field.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = focusGained()

            override fun focusLost(e: FocusEvent) {
                // Focus moving within the field's own panel is not leaving it.
                val opposite = e.oppositeComponent
                val within = opposite != null && panel?.let { SwingUtilities.isDescendingFrom(opposite, it) } == true
                focusLost(within)
            }
        })
        return panel
    }
}
