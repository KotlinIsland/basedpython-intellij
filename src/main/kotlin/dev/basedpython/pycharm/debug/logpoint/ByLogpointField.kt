package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.ShadowJava2DBorder
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.impl.ui.XDebuggerExpressionEditor
import dev.basedpython.pycharm.debug.ByDebuggerEditorsProvider
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JPanel

/**
 * The `Log:` box a log point shows in its gap, holding the expression it logs.
 *
 * One per log point, and it stays: a log point whose expression is invisible is a yellow dot that
 * does something unstated, which is what "there is no log point UI" meant when the `print` quick fix
 * made one without ever opening a field. So this is not a prompt that appears on creation and closes
 * on commit — it is how a log point looks.
 *
 * That also disposes of a whole class of bug. While the field could remove the breakpoint (an empty
 * one being useless *and invisible*), every stray focus event was a chance to destroy the user's
 * click, and one duly did. An empty log point is now visible like any other, so nothing here removes
 * anything; the gutter icon toggles a log point off exactly like a breakpoint.
 *
 * Styled to match IntelliJ IDEA's, since in IDEA this never runs and the two should not look like
 * different features: the same rounded shadow box, the same two colours, the same placeholder, and
 * the field taken from `getEditorComponent()` rather than `getComponent()`, which is what the
 * stray expand arrow was.
 */
class ByLogpointField private constructor(
    private val breakpoint: XLineBreakpoint<*>,
    internal val expressionEditor: XDebuggerExpressionEditor,
    private val hostEditor: EditorEx,
) : Disposable {

    /** The whole box, label included. Exposed so it can be laid out and painted in a test. */
    internal lateinit var component: JPanel
        private set

    private var inlay: Inlay<*>? = null
    private var closed = false

    override fun dispose() {
        inlay?.let { Disposer.dispose(it) }
        inlay = null
    }

    /** Writes what is in the box onto the log point. */
    fun commit() {
        if (closed) return
        val typed = typedExpression()
        breakpoint.logExpressionObject = if (typed.isEmpty()) null else expressionOf(typed)
    }

    /** Puts back what the log point actually says, discarding an edit in progress. */
    fun revert() {
        if (closed) return
        expressionEditor.expression = expressionOf(breakpoint.logExpressionObject?.expression.orEmpty())
    }

    /** Called when the log point goes; the box goes with it. */
    fun close() {
        if (closed) return
        closed = true
        if (breakpoint.getUserData(FIELD) === this) breakpoint.putUserData(FIELD, null)
        Disposer.dispose(this)
    }

    private fun typedExpression(): String = expressionEditor.expression?.expression?.trim().orEmpty()

    companion object {

        /**
         * Shows the box for [breakpoint] in [editor], or returns the one already there.
         *
         * Null when there is nowhere to put it — a line the document no longer has, or an editor
         * that cannot host components.
         */
        fun show(project: Project, editor: EditorEx, breakpoint: XLineBreakpoint<*>): ByLogpointField? {
            breakpoint.getUserData(FIELD)?.let { return it }

            val document = editor.document
            if (breakpoint.line !in 0 until document.lineCount) return null

            val expressionEditor = XDebuggerExpressionEditor(
                project,
                ByDebuggerEditorsProvider,
                HISTORY_ID,
                breakpoint.sourcePosition,
                expressionOf(breakpoint.logExpressionObject?.expression.orEmpty()),
                /* multiline = */ false,
                /* editorFont = */ true,
                /* showEditor = */ true,
            )

            val field = ByLogpointField(breakpoint, expressionEditor, editor)
            val panel = field.buildPanel()
            field.component = panel

            // showAbove: the gap a log point lives in is the one above the line it is anchored to.
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

            field.inlay = inlay
            breakpoint.putUserData(FIELD, field)
            Disposer.register(inlay) { field.close() }
            return field
        }

        /** The box currently showing for [breakpoint], if any. */
        fun of(breakpoint: XLineBreakpoint<*>): ByLogpointField? = breakpoint.getUserData(FIELD)

        private fun expressionOf(text: String) = XDebuggerUtil.getInstance()
            .createExpression(text, BasedPythonLanguage, null, EvaluationMode.EXPRESSION)

        private val FIELD = Key.create<ByLogpointField>("basedpython.logpoint.field")
        private const val HISTORY_ID = "basedpython-logpoint"

        /** IntelliJ IDEA's own `LogpointEditorColors`, so the two do not look like different features. */
        private val FIELD_BACKGROUND = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        private val FIELD_BORDER = JBColor(Color(0xC9CCD6), Color(0x393B40))

        private const val ARC = 8
        private const val FIELD_WIDTH = 320

        /** Room above and below one line of text, so the box is a box rather than a rule. */
        private const val FIELD_VERTICAL_PADDING = 8
        private const val LABEL = "Log:"
        private const val PLACEHOLDER = "Enter expression to log"
    }

    private fun buildPanel(): JPanel {
        // getEditorComponent(), not getComponent(): the latter wraps the field in the expand-to-a-
        // dialog affordance, which is the arrow that appeared beside the box and does not belong on
        // a one-line expression.
        val field = expressionEditor.editorComponent as? EditorTextField
        field?.apply {
            setOneLineMode(true)
            setPlaceholder(PLACEHOLDER)
            setShowPlaceholderWhenFocused(true)
            background = FIELD_BACKGROUND
            border = JBUI.Borders.empty(2, 6)
        }

        // Sized here rather than on the box. An EditorTextField that has not been shown yet has no
        // editor and reports almost no height, so fixing the *box's* preferred size took a snapshot
        // of that emptiness and kept it — which is how the box came out as a bar a few pixels tall,
        // with the real field inside it and nowhere to draw. The host editor's line height is what
        // one line of this text actually needs, and it is known before anything is displayed.
        field?.preferredSize = Dimension(
            JBUI.scale(FIELD_WIDTH),
            hostEditor.lineHeight + JBUI.scale(FIELD_VERTICAL_PADDING),
        )

        val box = BorderLayoutPanel()
        box.isOpaque = false
        box.border = ShadowJava2DBorder(JBUI.scale(ARC), FIELD_BACKGROUND, FIELD_BORDER)
        box.addToCenter(expressionEditor.editorComponent)

        val label = JBLabel(LABEL).apply {
            font = JBFont.small()
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(0, 8, 1, 0)
        }

        val panel = JPanel(BorderLayout())
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(1, 0, 2, 0)
        panel.add(label, BorderLayout.NORTH)
        panel.add(box, BorderLayout.WEST)

        val editorComponent = expressionEditor.editorComponent
        DumbAwareAction.create { commit() }.registerCustomShortcutSet(CommonShortcuts.ENTER, editorComponent, this)
        DumbAwareAction.create { revert() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, editorComponent, this)
        editorComponent.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = commit()
        })
        return panel
    }
}
