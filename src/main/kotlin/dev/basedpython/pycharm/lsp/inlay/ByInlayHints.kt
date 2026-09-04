package dev.basedpython.pycharm.lsp.inlay

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

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
     * The literal openings `by` writes each kind of hint with.
     *
     * Not patterns to match loosely: these are the exact strings its label constructors emit
     * (`ty_ide::InlayHint::inferred_raises`, `revealed_type`, `inferred_override` and friends), and
     * matching them is how the seventeen kinds are recovered from the two LSP carries.
     */
    private const val RAISES = "raises "
    private const val REVEALED = "revealed:"
    private const val OVERRIDE = "override"
    private const val REIFIED = "reified"
    private const val READS = "reads "
    private const val UNSTABLE = "unstable"
    private const val DEPENDS_ON = "depends on "
    private const val PROMOTION_BAR = "|"
    private const val TYPE_COLON = ":"
    private const val TYPE_ARGUMENT_BRACKET = "["
    private const val BINDS = "="

    /** The whole of what a variance hint can say, keyword for keyword. */
    private val VARIANCE_KEYWORDS = setOf("out", "in", "in out")

    /**
     * The hint's text, with the label's parts joined and nothing else done to it.
     *
     * `label` is `string | InlayHintLabelPart[]`; the parts carry per-part tooltips and go-to-def
     * targets, which this drops — the text is what gets drawn, and the parts of one hint are always
     * meant to be read as one string.
     *
     * Verbatim, whitespace included: `by` writes the spacing a hint needs into the label rather than
     * through `paddingLeft`/`paddingRight` (which it never sets). Its `override ` hint before a
     * `def` is a trailing space and nothing else, so trimming the label — which looks like the
     * tidy thing to do — is what would render `overridedef`.
     */
    fun labelOf(hint: InlayHint): String {
        val label = hint.label ?: return ""
        return when {
            label.isLeft -> label.left.orEmpty()
            label.isRight -> label.right.orEmpty().joinToString("") { it.value.orEmpty() }
            else -> ""
        }
    }

    /**
     * What the hint looks like on the wire, which is as much as can be told about it.
     *
     * LSP says only "type" or "parameter"; the rest is read off the label, and can be, because
     * `by`'s labels are fixed strings rather than free text. `override ` is written `override `
     * every time. See [ByHintShape].
     *
     * The two LSP kinds still do work: they split `name=` on an argument from `T=` on a type
     * argument, which are the same characters standing for different things, and they mark the
     * hints that name a parameter the source never spells.
     */
    fun shapeOf(hint: InlayHint, label: String): ByHintShape {
        val text = label.trim()
        if (hint.kind == InlayHintKind.Parameter) {
            return when {
                // `x=` names the argument that follows it; `ctx=value` *is* the argument.
                text.endsWith(BINDS) -> ByHintShape.ARGUMENT_NAME
                text.contains(BINDS) -> ByHintShape.IMPLICIT_ARGUMENT
                else -> ByHintShape.IMPLICIT_PARAMETER
            }
        }
        return when {
            text.startsWith(RAISES) -> ByHintShape.RAISES
            text.startsWith(REVEALED) -> ByHintShape.REVEALED_TYPE
            text == OVERRIDE -> ByHintShape.OVERRIDE
            text == REIFIED -> ByHintShape.REIFICATION
            text.startsWith(READS) -> ByHintShape.READS
            text == UNSTABLE -> ByHintShape.STABILITY
            text.startsWith(DEPENDS_ON) -> ByHintShape.DERIVED_DEPENDENCIES
            text in VARIANCE_KEYWORDS -> ByHintShape.VARIANCE
            text.startsWith(PROMOTION_BAR) -> ByHintShape.NUMERIC_PROMOTION
            text.startsWith(TYPE_ARGUMENT_BRACKET) -> ByHintShape.TYPE_ARGUMENTS
            text.endsWith(BINDS) -> ByHintShape.TYPE_ARGUMENT_NAME
            text.startsWith(TYPE_COLON) -> ByHintShape.TYPE
            else -> ByHintShape.UNKNOWN
        }
    }

    /** The hint's tooltip text, if it has one — lsp4j's `string | MarkupContent`. */
    fun tooltipOf(hint: InlayHint): String? {
        val tooltip = hint.tooltip ?: return null
        val text = when {
            tooltip.isLeft -> tooltip.left
            tooltip.isRight -> tooltip.right?.value
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }

    /** [text] as drawn, cut to [max] characters. */
    fun truncate(text: String, max: Int = MAX_CHARS): String =
        if (text.length <= max) text else text.take(max - ELLIPSIS.length) + ELLIPSIS
}
