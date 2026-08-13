package dev.basedpython.pycharm.lsp.inlay

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind

/**
 * What a hint is *about*, which is what each of the settings switches on.
 *
 * One per thing `by` writes, because they are not read the same way: the type of a binding is a
 * different question from what a call specialised its generic to, and either can be worth having in
 * front of you while the other is noise. Anything the plugin cannot place lands in [OTHER], which
 * has a setting of its own — so a hint `by` grows next release is switchable the day it appears
 * rather than stuck on whatever kind it happened to resemble.
 *
 * **Recognised by shape, not by LSP kind.** The protocol offers exactly two, [InlayHintKind.Type]
 * and [InlayHintKind.Parameter], which is not enough to tell a return annotation from a binding's,
 * let alone either from `override `. But a hint stands in for code, so it is written the way the
 * language writes that code, and the shape is the signal: `-> T` is a return, `: T` is a binding,
 * `[T]` is a type argument, and a bare word making room after itself adorns the declaration that
 * follows it. The one kind taken from LSP is [PARAMETER], which the protocol does say.
 *
 * Probed against `by ruff/0.0.1`: it emits `: T` on bindings, `[T]` on calls that specialise a
 * generic, `name=` on arguments and adornments like `override `. No `-> T` yet, so [RETURN_TYPE] is
 * what its setting will mean when it does.
 */
enum class ByHintKind {
    /** `foo(<name=>value)` — an argument's parameter name. */
    PARAMETER,

    /** `def f() <-> T>:` — an inferred return type. */
    RETURN_TYPE,

    /** `x<: T> = …` — an inferred type of a binding. */
    TYPE,

    /** `A<[int]>(1)` — what a call specialised a generic to. */
    TYPE_ARGUMENT,

    /** `<override >def f():` — a modifier the declaration carries without writing it. */
    MODIFIER,

    /** Anything else `by` sends, so that everything it sends has a setting. */
    OTHER,
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

    /** How an annotation is introduced in the language, and so how a binding's type hint opens. */
    private const val TYPE_COLON = ":"

    /** How a generic is subscripted, and so how a type-argument hint opens. */
    private const val TYPE_ARGUMENT_BRACKET = "["

    /** How an argument is named at a call site, and so how a parameter-name hint closes. */
    private const val NAMED_ARGUMENT_EQUALS = "="

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
     * What the hint is about — see [ByHintKind] for why this is read off the label rather than off
     * the LSP kind, which only ever says "type" or "parameter".
     *
     * A modifier is the one shape with two conditions: a bare word *and* a label that makes room
     * after itself, which is `override `'s trailing space. That space is how an adornment says it
     * prefixes the declaration, and requiring it keeps a one-word type name from being read as one.
     * Anything left over is [ByHintKind.OTHER] rather than forced into the nearest kind.
     *
     * `name=` is read as a parameter as well as taken from the LSP kind, since it is the shape of
     * one and a server that forgets the kind should not cost the setting its meaning.
     */
    fun kindOf(hint: InlayHint, label: String): ByHintKind {
        if (hint.kind == InlayHintKind.Parameter) return ByHintKind.PARAMETER
        val text = label.trim()
        return when {
            text.startsWith(RETURN_ARROW) -> ByHintKind.RETURN_TYPE
            text.startsWith(TYPE_COLON) -> ByHintKind.TYPE
            text.startsWith(TYPE_ARGUMENT_BRACKET) -> ByHintKind.TYPE_ARGUMENT
            isNamedArgument(text) -> ByHintKind.PARAMETER
            label.endsWith(' ') && text.isNotEmpty() && text.all(::isModifierChar) -> ByHintKind.MODIFIER
            else -> ByHintKind.OTHER
        }
    }

    /** `t=` — a name and the `=` that binds it, which is how an argument's name is written. */
    private fun isNamedArgument(text: String): Boolean =
        text.length > 1 && text.endsWith(NAMED_ARGUMENT_EQUALS) && text.dropLast(1).all(::isNameChar)

    /** A modifier is words: `override `, and whatever else `by` writes the same way. */
    private fun isModifierChar(c: Char): Boolean = c.isLetter() || c == '_' || c == ' '

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

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
     * Whether the inlay sits after the text it belongs to.
     *
     * It decides which side of the inlay the caret lands on when you type at exactly its offset, and
     * which side a selection swallows it with. A parameter name introduces the argument after it and
     * a modifier the declaration after it; every other hint completes the code before it.
     */
    fun relatesToPrecedingText(kind: ByHintKind): Boolean =
        kind != ByHintKind.PARAMETER && kind != ByHintKind.MODIFIER
}
