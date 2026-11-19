package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.presentation.PresentationFactory
import com.intellij.markdown.utils.doc.DocMarkdownToHtmlConverter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.LightweightHint
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ByHover
import dev.basedpython.pycharm.lsp.ByHoverRequest
import org.eclipse.lsp4j.Location
import java.awt.event.MouseEvent
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Timer

/**
 * What hovering a hint shows, which depends on where in the hint the mouse is.
 *
 * - Over a run of the label with a tooltip of its own (an LSP label part's `tooltip`), that.
 * - Over a run that names a place and has no tooltip of its own, the hint's own tooltip at once
 *   and, under it, what `by` says about the place: its hover there, the declaration and its
 *   docstring — what VS Code shows over such a part, and the same answer Quick Documentation gives
 *   on the name written out. Asked for after the mouse rests for [DOCS_DELAY_MS], so sweeping across
 *   a line of hints asks nothing, and kept for the hint's lifetime, which is one inlay pass. Both,
 *   because the hint's tooltip says what is particular to this hint and the place's docs do not:
 *   all of `override` names the member it overrides, and "Overrides `Animal.speak`" is the one line
 *   that says so — measured live, with the docs alone it never showed.
 * - Anywhere else, the hint's own tooltip, or failing that the untruncated label when the drawn
 *   one was cut ([fallback]).
 *
 * Every tooltip is HTML by the time it is shown, because the platform's hint label reads whatever
 * it is given as HTML: a `markdown` tooltip goes through the same [DocMarkdownToHtmlConverter] the
 * hover popup uses, and plain text — the untruncated label among it — is escaped, without which a
 * `<locals>` in a type would vanish as an unknown tag.
 *
 * EDT only, as mouse events are; the docs request is the one thing done elsewhere.
 */
class ByHintTooltip(
    private val editor: Editor,
    private val project: Project,
    private val hintTip: ByTip?,
    private val fallback: String?,
    private val spans: List<ByHintSpan>,
    /** Whether a run naming a place shows what `by` says about it. Off in the settings preview, which has no server. */
    private val docs: Boolean = true,
) {

    /** What is under the mouse: a run of the label, or the hint as a whole. */
    private sealed interface Target {
        data class Span(val span: ByHintSpan) : Target
        data object Whole : Target
    }

    private var current: Target? = null
    private var shown: LightweightHint? = null
    private var pending: Timer? = null

    /** `by`'s answer about each place asked about, as HTML; an empty string for "nothing to say". */
    private val answers = ConcurrentHashMap<Location, String>()

    /** Whether hovering shows anything at all — whether there is a tooltip anywhere in the hint. */
    val isEmpty: Boolean
        get() = hintTip == null && fallback == null && spans.none { it.tip != null || docs && it.location != null }

    /** The mouse moved over the hint, at [offset] into its drawn text, or between characters when null. */
    fun moved(event: MouseEvent, offset: Int?) {
        val span = offset?.let { o -> spans.firstOrNull { o >= it.start && o < it.end } }
        val target = span?.let { Target.Span(it) } ?: Target.Whole
        if (target == current && (shown?.isVisible == true || pending != null)) return
        hide()
        current = target
        show(target, event)
    }

    /** The mouse left the hint. */
    fun exited() {
        hide()
        current = null
    }

    /**
     * What hovering at [offset] shows before `by` has been asked anything, as HTML, or `null` for
     * nothing; over a run naming a place, [withDocs] is what replaces it once `by` has answered.
     */
    internal fun immediateHtml(offset: Int?): String? {
        val span = offset?.let { o -> spans.firstOrNull { o >= it.start && o < it.end } }
        span?.tip?.let { return html(it) }
        return wholeHtml()
    }

    /** The hint's own tooltip, if any, above `by`'s [answer] about a place, if it had one. */
    internal fun withDocs(answer: String): String? =
        listOfNotNull(wholeHtml(), answer.takeIf { it.isNotBlank() }).joinToString("<hr>").ifEmpty { null }

    private fun show(target: Target, event: MouseEvent) {
        val span = (target as? Target.Span)?.span
        span?.tip?.let { return display(html(it), event) }
        val location = span?.location
        if (docs && location != null) {
            answers[location]?.let { answer -> return display(withDocs(answer), event) }
            display(wholeHtml(), event)
            pending = Timer(DOCS_DELAY_MS) { ask(target, location, event) }.apply {
                isRepeats = false
                start()
            }
            return
        }
        display(wholeHtml(), event)
    }

    /** Asks `by` about [location], and shows the answer if the mouse is still over [target] when it comes. */
    private fun ask(target: Target, location: Location, event: MouseEvent) {
        pending = null
        if (current != target) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val answer = when (val hover = ByHoverRequest.at(project, location, HOVER_TIMEOUT_MS)) {
                is ByHover.Markup -> runReadActionBlocking {
                    DocMarkdownToHtmlConverter.convert(project, hover.text, BasedPythonLanguage)
                }
                ByHover.Nothing, ByHover.NoServer -> ""
            }
            answers[location] = answer
            ApplicationManager.getApplication().invokeLater({
                // still over the same run: the hint's tooltip, if one is up, gives way to it with the docs under it
                if (current == target && !editor.isDisposed && answer.isNotBlank()) {
                    shown?.hide()
                    shown = null
                    display(withDocs(answer), event)
                }
            }, ModalityState.any())
        }
    }

    private fun wholeHtml(): String? = hintTip?.let(::html) ?: fallback?.let(::escaped)

    private fun display(html: String?, event: MouseEvent) {
        if (html.isNullOrBlank() || !editor.contentComponent.isShowing) return
        shown = PresentationFactory(editor).showTooltip(event, html)
    }

    private fun hide() {
        pending?.stop()
        pending = null
        shown?.hide()
        shown = null
    }

    private fun html(tip: ByTip): String =
        if (tip.markdown) DocMarkdownToHtmlConverter.convert(project, tip.text, BasedPythonLanguage) else escaped(tip.text)

    private companion object {
        /** How long the mouse rests on a name before `by` is asked about it. */
        const val DOCS_DELAY_MS = 300

        /** Bounds a server that has stopped answering; the tooltip is not worth waiting longer for. */
        const val HOVER_TIMEOUT_MS = 2_000

        fun escaped(text: String): String = StringUtil.escapeXmlEntities(text).replace("\n", "<br>")
    }
}
