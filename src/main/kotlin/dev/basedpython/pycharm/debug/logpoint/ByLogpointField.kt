package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.CommonShortcuts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.EditorTextField
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.text.CharArrayUtil
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.components.BorderLayoutPanel
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.impl.ui.XDebuggerExpressionEditor
import dev.basedpython.pycharm.debug.ByDebuggerEditorsProvider
import java.awt.Color
import com.intellij.ui.components.JBLayeredPane
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent
import javax.swing.JPanel
import kotlin.math.ceil
import kotlin.math.floor
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.Collections
import java.util.WeakHashMap
import java.awt.Component
import java.awt.Insets
import javax.swing.border.Border

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

    /** What the inlay holds: [component], held at the indentation of the line it logs. */
    internal lateinit var indented: JComponent
        private set

    private var inlay: Inlay<*>? = null
    internal var closed = false
        private set

    /** Whether the file's own caret was showing when this box took focus; see [takeCaret]. */
    private var heldCaret = false

    override fun dispose() {
        // Before the inlay goes: disposing it takes the field's focus somewhere else, and the file
        // would be left with no caret at all if this box still had it.
        giveCaretBack()
        inlay?.let { Disposer.dispose(it) }
        inlay = null
    }

    /**
     * Stops the file's own caret being drawn while this box has the keyboard, so the file shows one
     * caret rather than two.
     *
     * There is no platform behaviour to lean on here. `EditorImpl.focusGained` activates its caret
     * and `focusLost` only stops the *blink* — it never passivates — so an editor that has been
     * focused once goes on painting a caret wherever it was left, and a box that takes focus without
     * saying anything simply adds a second one. Worse, the file does not even count as unfocused:
     * this box is a component inlay, so it lives inside the editor's own component and
     * `EditorImpl.isEditorOwningFocus` answers yes for it.
     *
     * `setCaretVisible` reports what it replaced, so [giveCaretBack] can put back exactly that
     * rather than assume — a file that never had focus should not gain a caret from having been
     * logged in.
     */
    private fun takeCaret() {
        if (heldCaret) return
        heldCaret = hostEditor.setCaretVisible(false)
    }

    /** Undoes [takeCaret]. A no-op unless there was a caret to hide. */
    private fun giveCaretBack() {
        if (!heldCaret) return
        heldCaret = false
        if (!hostEditor.isDisposed) hostEditor.setCaretVisible(true)
    }

    /** Writes what is in the box onto the log point. */
    fun commit() {
        if (closed) return
        val typed = typedExpression()
        breakpoint.logExpressionObject = if (typed.isEmpty()) null else expressionOf(typed)
    }

    /**
     * Puts back what the log point actually says, discarding an edit in progress.
     *
     * Under a write-intent read action, which is not ceremony: setting the expression makes the
     * editor build a fresh document, and building one resolves a context element out of the file
     * the breakpoint sits in. Being on the EDT is not access to the model on its own — a focus
     * event carries no lock — and this is reached from one, by way of [commit] telling the
     * breakpoint manager and the manager telling every listener back.
     */
    fun revert() {
        if (closed) return
        val expression = expressionOf(breakpoint.logExpressionObject?.expression.orEmpty())
        ApplicationManager.getApplication().runWriteIntentReadAction<Unit, RuntimeException> {
            expressionEditor.expression = expression
        }
    }

    /** Called when the log point goes; the box goes with it. */
    fun close() {
        if (closed) return
        closed = true
        fieldsIn(hostEditor)?.let { open -> if (open[breakpoint] === this) open.remove(breakpoint) }
        Disposer.dispose(this)
    }

    private fun typedExpression(): String = expressionEditor.expression?.expression?.trim().orEmpty()

    /**
     * The line the box is drawn above, as the document numbers it *now*.
     *
     * Taken from the inlay rather than from the breakpoint, because the inlay is what is on screen:
     * its offset moves with every edit above it, so the line it reports is the one the box is
     * actually sitting over. The breakpoint's own line is the fallback for the moment before the
     * inlay exists, which is when this is first asked — the holder measures itself while it is being
     * built.
     */
    private fun anchorLine(): Int {
        val document = hostEditor.document
        val offset = inlay?.takeIf { it.isValid }?.offset
        return if (offset != null && offset <= document.textLength) document.getLineNumber(offset) else breakpoint.line
    }

    companion object {

        /**
         * Shows the box for [breakpoint] in [editor], or returns the one already there.
         *
         * Null when there is nowhere to put it — a line the document no longer has, or an editor
         * that cannot host components.
         */
        fun show(project: Project, editor: EditorEx, breakpoint: XLineBreakpoint<*>): ByLogpointField? {
            // Null when the editor has no project to hold the box for. Nothing is drawn rather
            // than drawn into a map nobody keeps: a field the registry never received is an inlay
            // that no breakpoint event can ever close.
            val open = fieldsIn(editor) ?: return null
            open[breakpoint]?.let { if (!it.closed) return it else open.remove(breakpoint) }

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
            val indented = IndentedToCode(editor, panel) { field.anchorLine() }
            field.indented = indented

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
            val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(editor, indented, properties)
                ?: return null

            field.inlay = inlay
            open[breakpoint] = field
            Disposer.register(inlay) { field.close() }

            // The indentation is read when the box is laid out, and only a document change moves it.
            // Nothing else asks for that layout: the inlay's renderer re-reads its own bounds as the
            // document shifts, but re-indenting the logged line changes neither the renderer's
            // position nor its size, so without this the box stays at the column the line used to
            // start in.
            document.addDocumentListener(
                object : DocumentListener {
                    override fun documentChanged(event: DocumentEvent) {
                        indented.revalidate()
                        indented.repaint()
                    }
                },
                field,
            )
            return field
        }

        /** The box showing for [breakpoint] in [editor], if any. */
        fun of(editor: EditorEx, breakpoint: XLineBreakpoint<*>): ByLogpointField? = fieldsIn(editor)?.get(breakpoint)

        /**
         * The boxes open in [editor], keyed by their log point.
         *
         * Kept per editor rather than per breakpoint, which is what a closed and reopened tab used
         * to trip over: a breakpoint outlives every editor showing it, so a field parked on the
         * breakpoint was still there — disposed with the old editor — when the new one asked, and the
         * new editor got nothing but the gutter icon. It is also simply the truth, since one log
         * point in a split view is two boxes.
         *
         * In [ByLogpointFieldRegistry] rather than on the editor's own user data, where this lived:
         * an `Editor` is the platform's and outlives a plugin unload, so a box left on one keeps
         * this plugin's classloader alive and disabling the plugin reports *"didn't unload fully"*.
         */
        private fun fieldsIn(editor: EditorEx): MutableMap<XLineBreakpoint<*>, ByLogpointField>? =
            editor.project?.service<ByLogpointFieldRegistry>()?.fieldsIn(editor)

        private fun expressionOf(text: String) = ByLogpoints.expressionOf(text)

        private const val HISTORY_ID = "basedpython-logpoint"

        /** IntelliJ IDEA's own `LogpointEditorColors`, so the two do not look like different features. */
        private val FIELD_BACKGROUND = JBColor(Color(0xF7F8FA), Color(0x2B2D30))
        private val FIELD_BORDER = JBColor(Color(0xC9CCD6), Color(0x393B40))

        /** IntelliJ IDEA's `ShadowJava2DBorder(JBUI.scale(12), …)` corner, and its caption chip's. */
        private const val ARC = 12

        /** IntelliJ IDEA's `withPreferredWidth(JBUIScale.scale(500))`. */
        private const val FIELD_WIDTH = 500

        /** How narrow the box may get where the indentation leaves it no room. IntelliJ IDEA's number. */
        private const val MIN_FIELD_WIDTH = 200

        /**
         * Room between the box's rounded fill and the text field inside it — IntelliJ IDEA's
         * `JBUI.Borders.empty(8, 12, 8, 8)` on the panel that holds the expression editor.
         *
         * Not decoration: an `EditorTextField` paints its background as a *rectangle*, so one laid
         * straight into the bordered box covers the fill corner for corner and the box comes out
         * square. Measured in a running PyCharm — the fill was rounded and every corner of it was
         * painted over.
         */
        private val FIELD_INSETS = intArrayOf(8, 12, 8, 8)

        /** Above and below the box inside its inlay, so the shadow has somewhere to fall. IDEA's `empty(4, 0)`. */
        private const val BOX_VERTICAL_MARGIN = 4

        /** How much of the caption sits above the box, as IntelliJ IDEA measures it. */
        private const val CAPTION_RISE = 0.6

        /** How far in from the box's border the caption starts. IDEA's `insets.left + 12 - 6`. */
        private const val CAPTION_INDENT = 6

        /** How far the caption rises above the box, which is also how far it reaches into it. */
        fun overhangOf(caption: JComponent): Int =
            (ceil(caption.preferredSize.height * CAPTION_RISE).toInt() - JBUI.scale(4)).coerceAtLeast(0)

        /**
         * The caption while the box has focus — IntelliJ IDEA's `LogpointLabel.focusedForeground`,
         * read off `intellij.debugger.logpoints.frontend.jar` rather than guessed. Orange in a light
         * theme, yellow in a dark one, which is the log point's own colour: the gutter icon beside
         * the box is the same yellow.
         */
        private val ACTIVE_FOREGROUND = JBColor(Color(0xED820E), Color(0xF2C55C))
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
            border = JBUI.Borders.empty()
            // The editor inside draws its own border and paints its own background, and neither is
            // the text field's to set — which is why a second, inset rectangle appeared inside the
            // rounded box. A settings provider is the hook that runs whenever that editor is
            // created, including the times it is recreated later.
            addSettingsProvider { inner ->
                // No border of its own: the room around the text is the padding panel's now, and a
                // second inset here would only push the text off centre. IDEA does the same —
                // `editor.setBorder(null)` plus an empty border on the content component.
                inner.setBorder(null)
                inner.contentComponent.border = JBUI.Borders.empty()
                // The height reserved below is one host line plus padding, so the text has to be
                // host-sized or it does not fit in it — which is what cut the bottom off the glyphs.
                inner.setFontSize(hostEditor.colorsScheme.editorFontSize2D)
                inner.backgroundColor = FIELD_BACKGROUND
                inner.setPlaceholder(PLACEHOLDER)
                inner.setShowPlaceholderWhenFocused(true)
                inner.settings.isCaretRowShown = false
                inner.settings.isLineNumbersShown = false
                inner.settings.isUseSoftWraps = false
                inner.setVerticalScrollbarVisible(false)
                inner.setHorizontalScrollbarVisible(false)
            }
        }

        val label = CaptionLabel(hostEditor)

        // Sized from the host editor's line height rather than from the text field's own idea of
        // itself: one that has not been shown yet has no editor and reports almost no height, which
        // is how the box once came out as a bar a few pixels tall. Height only — the width is the
        // box's to decide, and it stretches to whatever the editor has room for.
        field?.preferredSize = Dimension(JBUI.scale(FIELD_WIDTH), hostEditor.lineHeight)

        // The padding between the rounded fill and the text field, and the whole reason the box has
        // corners: see FIELD_INSETS. It also carries the caption, whose lower two-fifths sit inside
        // the box's top border and would otherwise land on the first line of text.
        val padded = BorderLayoutPanel()
        padded.isOpaque = false
        padded.border = JBUI.Borders.empty(FIELD_INSETS[0], FIELD_INSETS[1], FIELD_INSETS[2], FIELD_INSETS[3])
        padded.addToCenter(expressionEditor.editorComponent)

        val box = BorderLayoutPanel()
        box.isOpaque = false
        box.border = ByLogpointBoxBorder(JBUI.scale(ARC), FIELD_BACKGROUND, FIELD_BORDER)
        box.addToCenter(padded)

        val editorComponent = expressionEditor.editorComponent
        DumbAwareAction.create { commit() }.registerCustomShortcutSet(CommonShortcuts.ENTER, editorComponent, this)
        DumbAwareAction.create { revert() }.registerCustomShortcutSet(CommonShortcuts.ESCAPE, editorComponent, this)
        editorComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) {
                label.setActive(true)
                takeCaret()
            }

            override fun focusLost(e: FocusEvent) {
                label.setActive(false)
                giveCaretBack()
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
            border = JBUI.Borders.empty(1, 6)
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

        private fun overhang(): Int = overhangOf(caption)

        override fun getPreferredSize(): Dimension = box.preferredSize.let {
            Dimension(it.width, it.height + overhang())
        }

        override fun getMinimumSize(): Dimension = preferredSize

        override fun doLayout() {
            val overhang = overhang()
            box.setBounds(0, overhang, width, (height - overhang).coerceAtLeast(0))
            val insets = box.border?.getBorderInsets(box) ?: JBUI.emptyInsets()
            val captionSize = caption.preferredSize
            // Straddling the box's top border rather than sitting on top of this component: the
            // border is drawn at `overhang + insets.top`, and IDEA puts the caption's lower two
            // fifths below that line. Anchoring at y = 0 instead left the notch off the border
            // wherever the shadow's inset and the overhang did not happen to agree.
            val captionY = floor(overhang + insets.top - captionSize.height * CAPTION_RISE).toInt()
            caption.setBounds(
                (insets.left + JBUI.scale(CAPTION_INDENT)).coerceAtLeast(0),
                captionY.coerceAtLeast(0),
                captionSize.width,
                captionSize.height,
            )
        }
    }

    /**
     * Holds the box at the indentation of the line it logs, rather than against the gutter.
     *
     * A block inlay is drawn at the left edge of the text — `BlockInlayImpl.getPosition` returns the
     * content component's left inset whatever offset the inlay is anchored to — so a box handed
     * straight to the inlay starts under the `d` of `def` however deep the line it belongs to is
     * indented, and reads as belonging to the wrong statement.
     *
     * The geometry is IntelliJ IDEA's own `XLogpointInlayLayoutManager`: the content sits at
     * `offsetToXY(firstNonWhitespace).x` of the anchored line, measured every time this is laid out
     * so a re-indent, a font change or a zoom is followed rather than remembered. A line that is
     * entirely whitespace indents to its end, which is where a statement typed on it would start.
     */
    private class IndentedToCode(
        private val hostEditor: EditorEx,
        private val box: JComponent,
        private val line: () -> Int,
    ) : JPanel(null) {

        init {
            isOpaque = false
            // IDEA's `JBUI.Borders.empty(4, 0, 4, 0)` on the same holder. The inlay is exactly as
            // tall as this component asks to be, so without the margin the box's shadow ends flush
            // against the line of code below it.
            border = JBUI.Borders.empty(BOX_VERTICAL_MARGIN, 0)
            add(box)
        }

        /** Where the code on the logged line starts, in this holder's own coordinates. */
        private fun indent(): Int {
            val document = hostEditor.document
            val line = line()
            if (line !in 0 until document.lineCount) return 0
            val start = document.getLineStartOffset(line)
            val end = document.getLineEndOffset(line)
            val code = CharArrayUtil.shiftForward(document.immutableCharSequence, start, end, " \t")
            return hostEditor.offsetToXY(code).x
        }

        override fun getPreferredSize(): Dimension = box.preferredSize.let {
            Dimension(indent() + it.width, it.height + verticalMargin())
        }

        override fun getMinimumSize(): Dimension = preferredSize

        override fun doLayout() {
            val indent = indent()
            val top = insets.top
            // The floor is IDEA's, and it is why the box overflows a narrow editor rather than
            // shrinking into it: an expression field a deep indent has squeezed to a few pixels is
            // worse than one that runs past the edge of a window the editor can scroll.
            box.setBounds(
                indent,
                top,
                (width - indent).coerceAtLeast(JBUI.scale(MIN_FIELD_WIDTH)),
                (height - verticalMargin()).coerceAtLeast(0),
            )
        }

        private fun verticalMargin(): Int = insets.let { it.top + it.bottom }
    }
}

/**
 * Every [ByLogpointField] open in this project, by the editor showing it.
 *
 * Two jobs, both of which the editor's own user data could not do. It holds the boxes somewhere
 * that dies with the plugin, so disabling basedpython does not leave an `Editor` — which is the
 * platform's, and survives — pointing at a component whose class has been unloaded. And disposing
 * it closes the boxes still on screen, which is the honest thing to do: a `Log:` box is this
 * plugin's, and without the plugin it is a text field that commits to nothing.
 *
 * Editors are held weakly, so a closed tab takes its entry with it exactly as before.
 */
@Service(Service.Level.PROJECT)
internal class ByLogpointFieldRegistry : Disposable {

    private val byEditor: MutableMap<EditorEx, MutableMap<XLineBreakpoint<*>, ByLogpointField>> =
        Collections.synchronizedMap(WeakHashMap())

    fun fieldsIn(editor: EditorEx): MutableMap<XLineBreakpoint<*>, ByLogpointField> =
        synchronized(byEditor) { byEditor.getOrPut(editor) { mutableMapOf() } }

    override fun dispose() {
        // Copied out first: closing a box removes it from the map it was found in.
        val open = synchronized(byEditor) { byEditor.values.flatMap { it.values.toList() } }
        open.forEach { it.close() }
        synchronized(byEditor) { byEditor.clear() }
    }
}

/**
 * The rounded, softly shadowed box the `Log:` field sits in.
 *
 * A border of our own rather than the platform's `ShadowJava2DBorder`, which is
 * `@ApiStatus.Internal` — see docs/internal-api.md. It is a small enough thing to draw that
 * reproducing it costs less than the dependency did: a filled round rectangle, a one-pixel outline,
 * and a few translucent passes underneath for the shadow.
 *
 * The shadow is drawn as [SHADOW_LAYERS] progressively larger, progressively fainter rounded
 * rectangles rather than with a blur: a Gaussian over the whole component is a temporary image and a
 * convolution on every repaint, and this box repaints on every keystroke in it. The insets reserve
 * room for the spread so the shadow is not clipped by the component's own bounds.
 */
internal class ByLogpointBoxBorder(
    private val arc: Int,
    private val fill: Color,
    private val outline: Color,
) : Border {

    override fun isBorderOpaque(): Boolean = false

    /** Room for the outline, plus the shadow's spread and its downward offset. */
    override fun getBorderInsets(c: Component): Insets {
        val spread = JBUI.scale(SHADOW_SPREAD)
        return JBUI.insets(spread, spread, spread + JBUI.scale(SHADOW_Y), spread)
    }

    override fun paintBorder(c: Component, g: Graphics, x: Int, y: Int, width: Int, height: Int) {
        val g2 = g.create() as? Graphics2D ?: return
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

            val spread = JBUI.scale(SHADOW_SPREAD)
            val offsetY = JBUI.scale(SHADOW_Y)
            val boxX = x + spread
            val boxY = y + spread
            val boxW = width - 2 * spread
            val boxH = height - 2 * spread - offsetY
            if (boxW <= 0 || boxH <= 0) return

            // Outermost and faintest first, so each layer darkens the ones already under it.
            for (layer in SHADOW_LAYERS downTo 1) {
                val grow = JBUI.scale(layer)
                g2.color = Color(0, 0, 0, SHADOW_ALPHA)
                g2.fillRoundRect(
                    boxX - grow,
                    boxY - grow + offsetY,
                    boxW + 2 * grow,
                    boxH + 2 * grow,
                    arc + grow,
                    arc + grow,
                )
            }

            g2.color = fill
            g2.fillRoundRect(boxX, boxY, boxW, boxH, arc, arc)
            g2.color = outline
            g2.drawRoundRect(boxX, boxY, boxW - 1, boxH - 1, arc, arc)
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        const val SHADOW_LAYERS = 4
        const val SHADOW_SPREAD = 5
        const val SHADOW_Y = 1
        /** Faint, because [SHADOW_LAYERS] of it accumulate. */
        const val SHADOW_ALPHA = 10
    }
}
