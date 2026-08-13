package dev.basedpython.pycharm.lsp.inlay

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

/**
 * What a hint is *about*, which is what the three settings toggles switch on.
 *
 * LSP only distinguishes [InlayHintKind.Type] from [InlayHintKind.Parameter], so "return type" —
 * which basedpython's settings have always offered as its own toggle — has to be recovered from the
 * label. A hint that stands in for a return annotation is written the way the language writes one,
 * `-> T`, because that is the code it replaces; the leading arrow is the whole signal, and a
 * binding's type hint (`: T`) never carries it.
 *
 * `by ruff/0.0.1` emits no return-type hints yet — only `: T` on bindings, `name=` on arguments and
 * a few adornments like `override ` — so [RETURN_TYPE] is what the toggle will mean when it does.
 * Until then that toggle switches nothing, which is what it did before this rendering existed too.
 */
enum class ByHintKind {
    /** `foo(<name=>value)` — an argument's parameter name. */
    PARAMETER,

    /** `def f() <-> T>:` — an inferred return type. */
    RETURN_TYPE,

    /** `x<: T> = …` — an inferred type of a binding. */
    TYPE,
}

/** One hint, flattened out of lsp4j into what the renderer needs. */
data class ByHint(
    val offset: Int,
    val text: String,
    val kind: ByHintKind,
    val padLeft: Boolean,
    val padRight: Boolean,
    val tooltip: String?,
)

/**
 * Turning `by`'s `textDocument/inlayHint` replies into [ByHint]s — the parts that are pure, and so
 * testable without a server, an editor or a project.
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

    /** The arrow every server writes a return-type hint with. */
    private const val RETURN_ARROW = "->"

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

    /** See [ByHintKind] for why a return type is recognised by its arrow rather than by a kind. */
    fun kindOf(hint: InlayHint, label: String): ByHintKind = when {
        hint.kind == InlayHintKind.Parameter -> ByHintKind.PARAMETER
        label.trimStart().startsWith(RETURN_ARROW) -> ByHintKind.RETURN_TYPE
        else -> ByHintKind.TYPE
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

    /**
     * Whether a hint of this kind is switched on.
     *
     * Kept here rather than read off the settings service so it can be tested as the table it is.
     */
    fun isEnabled(kind: ByHintKind, parameters: Boolean, types: Boolean, returns: Boolean): Boolean =
        when (kind) {
            ByHintKind.PARAMETER -> parameters
            ByHintKind.TYPE -> types
            ByHintKind.RETURN_TYPE -> returns
        }

    /**
     * Whether the inlay sits after the text it belongs to.
     *
     * It decides which side of the inlay the caret lands on when you type at exactly its offset, and
     * which side a selection swallows it with. A parameter-name hint introduces the argument that
     * follows it; every other hint completes the code before it.
     */
    fun relatesToPrecedingText(kind: ByHintKind): Boolean = kind != ByHintKind.PARAMETER
}
