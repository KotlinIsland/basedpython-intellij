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
import java.awt.Color
import com.intellij.ui.components.JBLayeredPane
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import kotlin.math.ceil
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent

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
    internal lateinit var component: JComponent
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

        /** How much of the caption sits above the box, as IntelliJ IDEA measures it. */
        private const val CAPTION_RISE = 0.6

        /** How far in from the box's border the caption starts. */
        private const val CAPTION_INDENT = 4

        private val ACTIVE_FOREGROUND = JBColor(Color(0x5A5D63), Color(0xCED0D6))
        private const val LABEL = "Log:"
        private const val PLACEHOLDER = "Enter expression to log"
    }

    private fun buildPanel(): JComponent {
        // getEditorComponent(), not getComponent(): the latter wraps the field in the expand-to-a-
        // dialog affordance, which is the arrow that appeared beside the box and does not belong on
        // a one-line expression.
        val field = expressionEditor.editorComponent as? EditorTextField
        field?.apply {
            setOneLineMode(true)
            setPlaceholder(PLACEHOLDER)
            setShowPlaceholderWhenFocused(true)
            background = FIELD_BACKGROUND
            border = JBUI.Borders.empty(2, 8)
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

        val label = CaptionLabel(hostEditor)

        val editorComponent = expressionEditor.editorComponent
        DumbAwareAction.create { commit() }.registerCustomShortcutSet(CommonShortcuts.ENTER, editorComponent, this)
        DumbAwareAction.create { revert() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, editorComponent, this)
        editorComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = label.setActive(true)

            override fun focusLost(e: FocusEvent) {
                label.setActive(false)
                commit()
            }
        })
        return CaptionedBox(box, label)
    }

    /**
     * The `Log:` caption, drawn sitting *on* the box's top border rather than above it.
     *
     * It paints the editor's own background behind itself first, as a rounded chip, so the border it
     * overlaps appears to break around the word — which is the whole of the difference between this
     * looking like IntelliJ IDEA's and looking like a label with a text field under it. Copied from
     * IDEA's `LogpointLabel`, including the antialiasing, without which the chip's corners tear
     * against the box's.
     */
    private class CaptionLabel(private val hostEditor: EditorEx) : JBLabel(LABEL) {

        init {
            isOpaque = false
            border = JBUI.Borders.empty(2, 6)
            foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        /**
         * Keeps the caption small across every look-and-feel change, which is what
         * `JBLabel(text, ComponentStyle.SMALL)` could not do here.
         *
         * That constructor applies the style through `updateUI`, and `updateUI` runs again when the
         * label joins a window — resetting the font to the theme's default. The label had by then
         * measured itself with the small font, so it asked for a height the text it went on to paint
         * did not fit in, and the top of every glyph was shaved off.
         */
        override fun updateUI() {
            super.updateUI()
            font = JBFont.small().deriveFont(java.awt.Font.PLAIN)
        }

        /**
         * Asks for whatever the text it is about to paint actually needs.
         *
         * `JLabel` sizes itself from the font it has *at the time it is asked*, and this label's font
         * changes when the look and feel is applied — so a height measured early was a height the
         * later, larger text did not fit in, and every glyph lost its top few pixels. Measuring the
         * current font here means the answer cannot go stale in the direction that clips.
         */
        override fun getPreferredSize(): Dimension {
            val preferred = super.getPreferredSize()
            val metrics = getFontMetrics(font)
            val needed = metrics.height + insets.top + insets.bottom
            return Dimension(preferred.width, maxOf(preferred.height, needed))
        }

        fun setActive(active: Boolean) {
            foreground = if (active) ACTIVE_FOREGROUND else JBUI.CurrentTheme.ContextHelp.FOREGROUND
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = hostEditor.colorsScheme.defaultBackground
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(ARC), JBUI.scale(ARC))
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    /**
     * Lays the caption over the box's top-left corner.
     *
     * The geometry is IDEA's: the caption overhangs the box by `ceil(captionHeight * 0.6) - 4`, so
     * roughly its top three-fifths sit above the border and the rest inside, and the box is inset
     * from the top by exactly that much. A layered pane rather than a border layout because the two
     * overlap — a caption in its own row is the version that looked wrong.
     */
    private class CaptionedBox(private val box: JComponent, private val caption: JComponent) : JBLayeredPane() {

        init {
            isOpaque = false
            add(box)
            add(caption)
            // Z-order, not layers. `add(component, PALETTE_LAYER)` left both on layer 0 here, and
            // Swing paints the *highest* index first — so the box, added first, was painting over
            // the caption and shaving the text. Index 0 is what paints last.
            setComponentZOrder(caption, 0)
        }

        private fun overhang(): Int =
            (ceil(caption.preferredSize.height * CAPTION_RISE).toInt() - JBUI.scale(4)).coerceAtLeast(0)

        override fun getPreferredSize(): Dimension = box.preferredSize.let {
            Dimension(it.width, it.height + overhang())
        }

        override fun getMinimumSize(): Dimension = preferredSize

        override fun doLayout() {
            val overhang = overhang()
            box.setBounds(0, overhang, width, (height - overhang).coerceAtLeast(0))
            val insets = box.border?.getBorderInsets(box) ?: JBUI.emptyInsets()
            val captionSize = caption.preferredSize
            caption.setBounds(insets.left + JBUI.scale(CAPTION_INDENT), 0, captionSize.width, captionSize.height)
        }
    }
}
