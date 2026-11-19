package dev.basedpython.pycharm.lsp.inlay

import com.google.gson.JsonObject
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.eclipse.lsp4j.jsonrpc.messages.Either

/**
 * Reading `by`'s `textDocument/inlayHint` replies — the parts that are pure, and so testable
 * without a server, an editor or a project.
 */
object ByInlayHints {

    /**
     * Longest hint drawn in full; anything longer is cut with an ellipsis and the whole text moves
     * to the tooltip.
     *
     * These are drawn in the editor font at editor size (see [ByInlayHintPresentation]), so a hint
     * costs exactly as much horizontal room as the same number of characters of code. A fully
     * expanded generic alias can run to hundreds of characters and would push the line off-screen.
     * The platform's own LSP renderer caps at 30, which is tuned for its half-size font; 60 is the
     * same *visual* width at full size.
     */
    const val MAX_CHARS: Int = 60

    private const val ELLIPSIS = "…"

    /**
     * The hint's text, with the label's parts joined and nothing else done to it.
     *
     * `label` is `string | InlayHintLabelPart[]`; the parts of one hint are always meant to be read
     * as one string, and this is that string. Where each part goes is [partsOf]'s.
     *
     * Verbatim, whitespace included: the gap around a hint is asked for separately, with
     * `paddingLeft`/`paddingRight` (`override` before a `def` arrives as `override` with
     * `paddingRight`), so whatever whitespace a label does hold is part of what it says.
     */
    fun labelOf(hint: InlayHint): String = partsOf(hint).joinToString("") { it.text }

    /**
     * The hint's label as the runs `by` sent it in, each with the place it names when it names one.
     *
     * `by` puts a location on the part of a hint that stands for something declared elsewhere — the
     * `list` and the `int` of `: list[int]`, the parameter a `name=` names, the class an `override`
     * overrides — and nothing on the punctuation between them. A part may also carry a tooltip of
     * its own and a command a click on it runs, which LSP allows on any part.
     */
    fun partsOf(hint: InlayHint): List<ByHintPart> {
        val label = hint.label ?: return emptyList()
        return when {
            label.isLeft -> listOf(ByHintPart(label.left.orEmpty(), null))
            label.isRight -> label.right.orEmpty().map {
                ByHintPart(it.value.orEmpty(), it.location, tipOf(it.tooltip), it.command?.takeIf { c -> !c.command.isNullOrBlank() })
            }
            else -> emptyList()
        }
    }

    /**
     * The runs of [drawn] that have something of their own to show or do — a place, a tooltip or a
     * command — as character ranges into it, cut as [linksOf] cuts its links.
     */
    fun spansOf(parts: List<ByHintPart>, drawn: String): List<ByHintSpan> {
        val label = parts.joinToString("") { it.text }
        val visible = if (drawn == label) drawn.length else drawn.length - ELLIPSIS.length
        val spans = ArrayList<ByHintSpan>()
        var start = 0
        for (part in parts) {
            val end = start + part.text.length
            val worthASpan = part.location != null || part.tip != null || part.command != null
            if (worthASpan && start < visible) {
                spans += ByHintSpan(start, minOf(end, visible), part.location, part.tip, part.command)
            }
            start = end
        }
        return spans
    }

    /**
     * The runs of [drawn] — the hint's text as drawn, which [truncate] may have cut — that go
     * somewhere, as character ranges into it.
     *
     * A run the cut reaches is shortened to what is still drawn, and one it removes entirely is
     * gone: the ellipsis stands for the text it replaced and goes nowhere of its own. Neighbouring
     * runs naming the same place stay two links, as `by` sent them.
     */
    fun linksOf(parts: List<ByHintPart>, drawn: String): List<ByHintLink> {
        val label = parts.joinToString("") { it.text }
        val visible = if (drawn == label) drawn.length else drawn.length - ELLIPSIS.length
        val links = ArrayList<ByHintLink>()
        var start = 0
        for (part in parts) {
            val end = start + part.text.length
            val location = part.location
            if (location != null && start < visible) links += ByHintLink(start, minOf(end, visible), location)
            start = end
        }
        return links
    }

    /**
     * The kind of hint this is, as `by` tagged it: `data.kind`, the name of the `inlayHints` option
     * that switches it (`"revealedTypes"`, `"inferredOverride"`, …).
     *
     * Read from that tag and nothing else. LSP's own `kind` carries two values for the two dozen
     * things `by` distinguishes, and the label is what the hint says, not what it is: a revealed
     * `list[int]` and a call's `[int]` are one bracket apart, and every label format `by` has ever
     * changed moved hints between settings without anyone noticing. A hint with no tag — from a `by`
     * that does not send one — is [ByHintKind.UNTAGGED].
     */
    fun kindOf(hint: InlayHint): ByHintKind {
        val data = hint.data as? JsonObject ?: return ByHintKind.UNTAGGED
        val tag = data.get(KIND_TAG)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
        return tag?.asString?.let(::ByHintKind) ?: ByHintKind.UNTAGGED
    }

    /** The member of a hint's `data` that `by` names its kind in. */
    private const val KIND_TAG = "kind"

    /**
     * Where the hint goes: in the line at its position, as every LSP hint does, or as lines of its
     * own above or below the line its position is on — `data.placement`, `"above"` or `"below"`.
     *
     * Read from the hint, never from its kind, so that any kind `by` places on lines of its own is
     * drawn there, the day it does: a member a stub declares and the runtime module lacks
     * (`stubOnlyMembers`) is the first, and nothing here names it. A placement this plugin does not
     * know, or none, is [ByHintPlacement.INLINE].
     */
    fun placementOf(hint: InlayHint): ByHintPlacement {
        val data = hint.data as? JsonObject ?: return ByHintPlacement.INLINE
        val tag = data.get(PLACEMENT_TAG)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
        return ByHintPlacement.entries.firstOrNull { it.wire != null && it.wire == tag } ?: ByHintPlacement.INLINE
    }

    /** The member of a hint's `data` that `by` says where a hint goes in. */
    private const val PLACEMENT_TAG = "placement"

    /** The hint's tooltip text, if it has one — lsp4j's `string | MarkupContent`. */
    fun tooltipOf(hint: InlayHint): String? = tipOf(hint.tooltip)?.text

    /** The hint's tooltip, with whether it is markdown, if it has one. */
    fun tipOf(hint: InlayHint): ByTip? = tipOf(hint.tooltip)

    /**
     * A tooltip as LSP sends one, `string | MarkupContent`: a plain string is plain text, and so is
     * markup whose kind is anything but `markdown`. A blank one is none.
     */
    fun tipOf(tooltip: Either<String, MarkupContent>?): ByTip? {
        tooltip ?: return null
        val tip = when {
            tooltip.isLeft -> tooltip.left?.let { ByTip(it, markdown = false) }
            tooltip.isRight -> tooltip.right?.let { ByTip(it.value.orEmpty(), markdown = it.kind == MarkupKind.MARKDOWN) }
            else -> null
        }
        return tip?.takeIf { it.text.isNotBlank() }
    }

    /** [text] as drawn, cut to [max] characters. */
    fun truncate(text: String, max: Int = MAX_CHARS): String =
        if (text.length <= max) text else text.take(max - ELLIPSIS.length) + ELLIPSIS
}

/** Where a hint is drawn, as [ByInlayHints.placementOf] reads it; [wire] is `data.placement`. */
enum class ByHintPlacement(val wire: String?) {
    /** In the line, at its position. */
    INLINE(null),

    /** Lines of its own, above the line its position is on. */
    ABOVE("above"),

    /** Lines of its own, below the line its position is on. */
    BELOW("below"),
    ;

    val isBlock: Boolean get() = this != INLINE
}

/**
 * One run of a hint's label, and the place it names — `null` for the punctuation between names —
 * with the tooltip and the command `by` gave this run, if any.
 */
data class ByHintPart(
    val text: String,
    val location: Location?,
    val tip: ByTip? = null,
    val command: Command? = null,
)

/** A tooltip's [text], which is markdown when [markdown] and plain text otherwise. */
data class ByTip(val text: String, val markdown: Boolean)

/** Characters [start] until [end] of a drawn hint, and what that run of it names, says and does. */
data class ByHintSpan(
    val start: Int,
    val end: Int,
    val location: Location?,
    val tip: ByTip?,
    val command: Command?,
) {
    val run: ByHintRun get() = ByHintRun(start, end)
}

/** Characters [start] until [end] of a drawn hint, which name [location]. */
data class ByHintLink(val start: Int, val end: Int, val location: Location) {
    val run: ByHintRun get() = ByHintRun(start, end)
}

/** Characters [start] until [end] of a drawn hint: the run drawn as a hyperlink while the mouse is on it. */
data class ByHintRun(val start: Int, val end: Int)
