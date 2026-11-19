package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.util.EditorUIUtil
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.GraphicsUtil
import dev.basedpython.pycharm.lang.BasedPythonSyntaxHighlighter
import org.eclipse.lsp4j.Location
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A hint that is lines of code of its own rather than a few characters in a line: a member a stub
 * declares and the runtime module does not, drawn where the module would declare it —
 *
 * ```
 * class A(B):
 *     def f(self, a: int) -> None: ...
 *
 *     def g(self, a: int) -> None: ...      <- these lines are the hint
 * ```
 *
 * — any hint `by` places above or below a line ([ByHintPlacement]), as a block inlay there.
 *
 * Drawn as the code it is: the editor's font at the editor's size, each line at the column its own
 * leading whitespace puts it — the label carries the indentation in full, so line *n* of it lines up
 * with code indented as far — and highlighted by basedpython's lexer, every colour faded towards the
 * background as an inline hint's text is ([ByInlayColors]), on the same faint tint. A tint and a
 * fade both, for the reason [ByInlayColors] gives: faded code alone is how the IDE draws code that
 * does not run.
 *
 * Push-to-hint, clicks and double clicks are [ByInlayHintPresentation]'s: hidden until the push key
 * goes down in [ByHintMode.ON_PUSH], taking no room while hidden; a run of the label that names
 * something ([links]) goes there on Ctrl+click or a middle click and underlines under Ctrl; a double
 * click writes the hint into the source when `by` sent the edit that does ([accept]); a plain click
 * on a run with a command runs it; the popup trigger opens [ByHintMenu]; hovering shows [tooltip].
 */
class ByBlockHintPresentation(
    override val editor: Editor,
    text: String,
    private val mode: ByHintMode = ByHintMode.ALWAYS,
    private val pushKey: ByPushKey = ByPushKey.CTRL_ALT,
    links: List<ByHintLink> = emptyList(),
    private val navigate: ((Location) -> Unit)? = null,
    private val accept: (() -> Unit)? = null,
    private val menu: ByHintMenu? = null,
    /** What hovering shows; its offsets are the label's as sent, which [ByBlockText.labelOffset] maps back to. */
    private val tooltip: ByHintTooltip? = null,
    commands: List<ByHintSpan> = emptyList(),
    private val runCommand: ((org.eclipse.lsp4j.Command) -> Unit)? = null,
) : BasePresentation(), ByHintPush.Watcher {

    /** The label as drawn: its tabs laid out to the editor's tab stops, so a column is a character. */
    private val shape = ByBlockText.of(text, EditorUtil.getTabSize(editor))

    /** The label's text as drawn, lines joined by `\n`. */
    val text: String get() = shape.text

    private val links: List<ByHintLink> = links.mapNotNull { shape.mapLink(it) }

    /** The runs with a command of their own, as offsets of [text]. */
    private val commands: List<ByHintSpan> = commands.mapNotNull { shape.mapSpan(it) }

    /** The lexer's runs over [text], with the keys the highlighter gives each; colours are read when painting, so a scheme change is seen. */
    private val runs: List<Run> = lex(shape.text)

    @Volatile
    private var shown: Boolean = mode.isShown(ByHintPush.getInstance().isHeld(pushKey))

    /** The link under the mouse while Ctrl is held. EDT only. */
    private var hovered: ByHintRun? = null

    init {
        if (mode == ByHintMode.ON_PUSH) ByHintPush.getInstance().watch(this)
    }

    /** The longest line's advance; nought while hidden, which a block inlay may be. */
    override val width: Int
        get() {
            if (!shown) return 0
            val metrics = metrics(Font.PLAIN)
            return shape.lines.maxOfOrNull { advance(metrics, it, it.length).roundToInt() } ?: 0
        }

    /** A line box per line of the label; nought while hidden. */
    override val height: Int
        get() = if (shown) editor.lineHeight * shape.lines.size else 0

    override fun paint(g: Graphics2D, attributes: TextAttributes) {
        if (!shown) return
        val scheme = editor.colorsScheme
        val hint = ByInlayColors.attributes(scheme)
        val savedFont = g.font
        val savedColor = g.color
        try {
            val plain = metrics(Font.PLAIN)
            hint.backgroundColor?.let { paintTint(g, plain, it) }
            EditorUIUtil.setupAntialiasing(g)
            val reference = scheme.getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)?.foregroundColor
            val link = hovered
            for (run in runs) {
                val (color, fontType) = style(scheme, run.keys)
                val faded = ByInlayColors.derivedForeground(color, scheme.defaultBackground)
                if (link == null) {
                    paintRun(g, run.start, run.end, faded, fontType)
                } else {
                    // the link's characters are drawn once, as a link, below
                    paintRun(g, run.start, minOf(run.end, link.start), faded, fontType)
                    paintRun(g, max(run.start, link.end), run.end, faded, fontType)
                }
            }
            link?.let { link ->
                val color = reference ?: hint.foregroundColor
                paintRun(g, link.start, link.end, color, Font.PLAIN, underline = true)
            }
        } finally {
            g.font = savedFont
            g.color = savedColor
        }
    }

    /** Characters [start] until [end] of [text], line by line, each at the x the characters before it on its line reach. */
    private fun paintRun(g: Graphics2D, start: Int, end: Int, color: Color, fontType: Int, underline: Boolean = false) {
        val metrics = metrics(fontType)
        g.font = metrics.font
        g.color = color
        for ((index, line) in shape.lines.withIndex()) {
            val lineStart = shape.lineStarts[index]
            val from = max(start, lineStart) - lineStart
            val to = minOf(end, lineStart + line.length) - lineStart
            if (from >= to) continue
            val x = advance(metrics, line, from)
            val baseline = baseline(metrics) + index * editor.lineHeight
            g.drawString(line.substring(from, to), x, baseline.toFloat())
            if (underline) {
                g.drawLine(x.roundToInt(), baseline + 1, advance(metrics, line, to).roundToInt() - 1, baseline + 1)
            }
        }
    }

    /**
     * One tint behind the lines, from the least indented line's first character to the end of the
     * longest: the block reads as one piece of code that is not there, rather than a box per line.
     * Inset vertically as an inline hint's is, so stacked blocks and the code around them stay apart.
     */
    private fun paintTint(g: Graphics2D, metrics: FontMetrics, color: Color) {
        val left = shape.lines.filter { it.isNotBlank() }.minOfOrNull { line ->
            advance(metrics, line, line.length - line.trimStart().length)
        }?.roundToInt() ?: return
        val right = width
        if (right <= left) return
        // from the first line with code to the last: a blank line the label starts with is spacing,
        // as it would be in the source, not part of what is not there
        val first = shape.lines.indexOfFirst { it.isNotBlank() }
        val last = shape.lines.indexOfLast { it.isNotBlank() }
        val top = first * editor.lineHeight + VERTICAL_INSET
        val bottom = (last + 1) * editor.lineHeight - VERTICAL_INSET
        val config = GraphicsUtil.setupAAPainting(g)
        try {
            g.color = color
            g.fillRoundRect(left, top, right - left, bottom - top, ARC, ARC)
        } finally {
            config.restore()
        }
    }

    /** The colour and font style [keys] give, the later keys over the earlier as the editor layers them. */
    private fun style(scheme: EditorColorsScheme, keys: Array<TextAttributesKey>): Pair<Color, Int> {
        var color = scheme.defaultForeground
        var fontType = Font.PLAIN
        for (key in keys) {
            val attributes = scheme.getAttributes(key) ?: continue
            attributes.foregroundColor?.let { color = it }
            if (attributes.fontType != Font.PLAIN) fontType = attributes.fontType
        }
        return color to fontType
    }

    /** The link drawn at [point], relative to this hint, or `null`. */
    internal fun linkAt(point: Point): ByHintLink? {
        if (links.isEmpty()) return null
        val offset = offsetAt(point) ?: return null
        return links.firstOrNull { offset >= it.start && offset < it.end }
    }

    /** The offset into [text] of the character drawn at [point], relative to this hint, or `null`. */
    internal fun offsetAt(point: Point): Int? {
        if (!shown || point.y < 0) return null
        val index = point.y / editor.lineHeight
        val line = shape.lines.getOrNull(index) ?: return null
        val metrics = metrics(Font.PLAIN)
        val column = (0 until line.length).firstOrNull { point.x < advance(metrics, line, it + 1) } ?: return null
        return shape.lineStarts[index] + column
    }

    private fun commandAt(point: Point): ByHintSpan? {
        if (commands.isEmpty() || runCommand == null) return null
        val offset = offsetAt(point) ?: return null
        return commands.firstOrNull { offset >= it.start && offset < it.end }
    }

    override fun mouseMoved(event: MouseEvent, translated: Point) {
        hover(if (isControlDown(event)) linkAt(translated)?.run else commandAt(translated)?.run)
        if (shown) tooltip?.moved(event, offsetAt(translated)?.let(shape::labelOffset))
    }

    override fun mouseExited() {
        hover(null)
        tooltip?.exited()
    }

    override fun mouseClicked(event: MouseEvent, translated: Point) {
        val plainClick = SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1 &&
            !isControlDown(event) && !event.isShiftDown && !event.isAltDown
        if (plainClick) {
            val command = commandAt(translated)?.command
            if (command != null) {
                runCommand?.invoke(command)
                return
            }
        }
        val navigate = navigate ?: return
        val linkGesture = SwingUtilities.isMiddleMouseButton(event) ||
            SwingUtilities.isLeftMouseButton(event) && isControlDown(event)
        if (!linkGesture) return
        val link = linkAt(translated) ?: return
        hover(null)
        navigate(link.location)
    }

    /** As [ByInlayHintPresentation.mousePressed]: the second press of a double click, kept from the editor. */
    override fun mousePressed(event: MouseEvent, translated: Point) {
        if (openMenu(event)) return
        if (accept == null || !shown || !SwingUtilities.isLeftMouseButton(event)) return
        if (event.clickCount != 2 || isControlDown(event)) return
        event.consume()
        accept()
    }

    /** The release is the popup trigger on Windows, as the press is elsewhere. */
    override fun mouseReleased(event: MouseEvent, translated: Point) {
        openMenu(event)
    }

    /**
     * Opens [menu] on the popup trigger over a hint that is drawn, consuming the event so that the
     * editor neither moves the caret nor opens its own context menu over this one.
     */
    private fun openMenu(event: MouseEvent): Boolean {
        val menu = menu ?: return false
        if (!shown || !ByHintMenu.isTrigger(event)) return false
        event.consume()
        hover(null)
        menu.show(event)
        return true
    }

    private fun hover(link: ByHintRun?) {
        if (link == hovered) return
        hovered = link
        (editor as? EditorEx)?.setCustomCursor(CURSOR_REQUESTOR, if (link == null) null else Cursor.getPredefinedCursor(Cursor.HAND_CURSOR))
        fireContentChanged(Rectangle(0, 0, width, height))
    }

    private fun isControlDown(event: MouseEvent): Boolean = if (SystemInfo.isMac) event.isMetaDown else event.isControlDown

    override fun pushStateChanged() {
        val next = mode.isShown(ByHintPush.getInstance().isHeld(pushKey))
        if (next == shown) return
        val before = Dimension(width, height)
        shown = next
        val after = Dimension(width, height)
        fireSizeChanged(before, after)
        fireContentChanged(Rectangle(0, 0, after.width, after.height))
    }

    /** As [ByInlayHintPresentation.updateState]: the same text under a different mode is a different hint. */
    override fun updateState(previousPresentation: InlayPresentation): Boolean {
        val previous = previousPresentation as? ByBlockHintPresentation ?: return true
        return previous.text != text || previous.mode != mode || previous.pushKey != pushKey
    }

    /** The editor's own baseline in a line box, as [ByInlayHintPresentation] puts its text on it. */
    private fun baseline(metrics: FontMetrics): Int =
        (editor.lineHeight - (metrics.ascent + metrics.descent)) / 2 + metrics.ascent

    /** How far the first [end] characters of [line] reach, fractional, as the editor lays a line out. */
    private fun advance(metrics: FontMetrics, line: String, end: Int): Float =
        if (end <= 0) 0f else metrics.font.getStringBounds(line, 0, end, metrics.fontRenderContext).width.toFloat()

    private fun metrics(fontType: Int): FontMetrics {
        val plain = editor.colorsScheme.getFont(EditorFontType.PLAIN)
        return editor.contentComponent.getFontMetrics(if (fontType == Font.PLAIN) plain else plain.deriveFont(fontType))
    }

    override fun toString(): String = text

    private class Run(val start: Int, val end: Int, val keys: Array<TextAttributesKey>)

    private companion object {
        val VERTICAL_INSET: Int = JBUIScale.scale(1)
        val ARC: Int = JBUIScale.scale(2)
        const val CURSOR_REQUESTOR = "basedpython.inlay.link"

        fun lex(text: String): List<Run> {
            val highlighter = BasedPythonSyntaxHighlighter()
            val lexer = highlighter.highlightingLexer
            lexer.start(text)
            val runs = ArrayList<Run>()
            while (true) {
                val type = lexer.tokenType ?: break
                runs += Run(lexer.tokenStart, lexer.tokenEnd, highlighter.getTokenHighlights(type))
                lexer.advance()
            }
            return runs
        }
    }
}

/**
 * A block hint's label as drawn: [text] with each tab laid out to the next tab stop, its [lines], the
 * offset each line starts at in [text], and where an offset of the label as sent landed.
 */
internal class ByBlockText private constructor(val text: String, private val offsets: IntArray) {

    val lines: List<String> = text.split('\n')

    val lineStarts: IntArray = IntArray(lines.size).also { starts ->
        var at = 0
        for ((index, line) in lines.withIndex()) {
            starts[index] = at
            at += line.length + 1
        }
    }

    /** [link], an offset range of the label as sent, as one of [text]. */
    fun mapLink(link: ByHintLink): ByHintLink? {
        if (link.start !in offsets.indices || link.end !in offsets.indices) return null
        return ByHintLink(offsets[link.start], offsets[link.end], link.location)
    }

    /** [span], an offset range of the label as sent, as one of [text]. */
    fun mapSpan(span: ByHintSpan): ByHintSpan? {
        if (span.start !in offsets.indices || span.end !in offsets.indices) return null
        return span.copy(start = offsets[span.start], end = offsets[span.end])
    }

    /**
     * The offset of the label as sent whose character is drawn at [textOffset] of [text]: the last
     * one laid out at or before it, so a column inside a laid-out tab is the tab's.
     */
    fun labelOffset(textOffset: Int): Int {
        var found = 0
        for (i in 0 until offsets.size - 1) {
            if (offsets[i] <= textOffset) found = i else break
        }
        return found
    }

    companion object {
        /** [label] with its tabs laid out to stops [tabSize] apart, counted from each line's start. */
        fun of(label: String, tabSize: Int): ByBlockText {
            val out = StringBuilder(label.length)
            // offsets[i] is where character i of the label starts in the text; one past the end too
            val offsets = IntArray(label.length + 1)
            var column = 0
            for ((index, char) in label.withIndex()) {
                offsets[index] = out.length
                when (char) {
                    '\t' -> {
                        val width = tabSize.coerceAtLeast(1) - column % tabSize.coerceAtLeast(1)
                        repeat(width) { out.append(' ') }
                        column += width
                    }
                    '\n' -> {
                        out.append(char)
                        column = 0
                    }
                    else -> {
                        out.append(char)
                        column++
                    }
                }
            }
            offsets[label.length] = out.length
            return ByBlockText(out.toString(), offsets)
        }
    }
}
