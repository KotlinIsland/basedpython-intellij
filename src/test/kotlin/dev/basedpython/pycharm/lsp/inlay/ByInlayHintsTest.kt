package dev.basedpython.pycharm.lsp.inlay

import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for [ByInlayHints] — the lsp4j-shaped half of the hints, with no editor, project
 * or server anywhere near it.
 */
class ByInlayHintsTest {

    private fun hint(
        label: Either<String, List<InlayHintLabelPart>>,
        kind: InlayHintKind? = null,
    ): InlayHint = InlayHint(Position(0, 0), label).also { it.kind = kind }

    private fun textHint(text: String, kind: InlayHintKind? = null): InlayHint =
        hint(Either.forLeft(text), kind)

    // region: label flattening

    @Test
    fun `a string label is its own text`() {
        assertEquals(": int", ByInlayHints.labelOf(textHint(": int")))
    }

    @Test
    fun `label parts are joined into one string`() {
        val parts = listOf(InlayHintLabelPart(": "), InlayHintLabelPart("list["), InlayHintLabelPart("int]"))
        assertEquals(": list[int]", ByInlayHints.labelOf(hint(Either.forRight(parts))))
    }

    @Test
    fun `the label's own spacing survives`() {
        // `by`'s `override ` hint before a `def` is a word and a trailing space; trimming it would
        // render `overridedef`.
        assertEquals("override ", ByInlayHints.labelOf(textHint("override ")))
        assertEquals(
            "override ",
            ByInlayHints.labelOf(hint(Either.forRight(listOf(InlayHintLabelPart("override "))))),
        )
    }

    @Test
    fun `a missing label is empty rather than null`() {
        assertEquals("", ByInlayHints.labelOf(InlayHint()))
    }

    // endregion

    // region: kind

    @Test
    fun `a Parameter-kind hint is a parameter hint`() {
        val hint = textHint("value:", InlayHintKind.Parameter)
        assertEquals(ByHintKind.PARAMETER, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `a Type-kind hint starting with an arrow is a return-type hint`() {
        val hint = textHint("-> bool", InlayHintKind.Type)
        assertEquals(ByHintKind.RETURN_TYPE, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `an arrow hint with no kind at all is still a return-type hint`() {
        val hint = textHint("-> bool")
        assertEquals(ByHintKind.RETURN_TYPE, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `an arrow behind the label's own leading space is still an arrow`() {
        val hint = textHint(" -> bool", InlayHintKind.Type)
        assertEquals(ByHintKind.RETURN_TYPE, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `a Type-kind hint without an arrow is an ordinary type hint`() {
        val hint = textHint(": list[int]", InlayHintKind.Type)
        assertEquals(ByHintKind.TYPE, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `a name and an equals is an argument's name even with no LSP kind on it`() {
        // The kind is what `by` sends; the shape is what keeps the setting meaningful if it stops.
        val hint = textHint("t=")
        assertEquals(ByHintKind.PARAMETER, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `an equals that is not a name being bound is not a parameter`() {
        for (label in listOf("=", "a + b=", "!=")) {
            val hint = textHint(label)
            assertEquals(
                ByHintKind.OTHER,
                ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)),
                "\"$label\" is not an argument's name",
            )
        }
    }

    @Test
    fun `a subscript is what a call specialised a generic to`() {
        // `a = A(1)` drawn as `a: A[int] = A[int](t=1)`: the second `[int]` is this hint.
        val hint = textHint("[int]", InlayHintKind.Type)
        assertEquals(ByHintKind.TYPE_ARGUMENT, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `a word making room after itself adorns what follows`() {
        for (label in listOf("override ", "final ", "async override ")) {
            val hint = textHint(label)
            assertEquals(
                ByHintKind.MODIFIER,
                ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)),
                "\"$label\" should read as a modifier",
            )
        }
    }

    @Test
    fun `a word that makes no room after itself is not a modifier`() {
        // The trailing space is how an adornment says it prefixes a declaration. Without it this is
        // some other thing `by` sends, and it belongs in the kind that has a setting for exactly
        // that rather than in the nearest-looking one.
        val hint = textHint("int")
        assertEquals(ByHintKind.OTHER, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    @Test
    fun `a shape the plugin cannot place is its own kind, not the nearest one`() {
        for (label in listOf("=> 3", "(mut)", "«borrowed»")) {
            val hint = textHint(label, InlayHintKind.Type)
            assertEquals(
                ByHintKind.OTHER,
                ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)),
                "\"$label\" should fall to the kind that has a setting for anything else",
            )
        }
    }

    @Test
    fun `a parameter named after an arrow-like operator is still a parameter`() {
        // Kind wins over the arrow heuristic, so a parameter hint can never be read as a return.
        val hint = textHint("->", InlayHintKind.Parameter)
        assertEquals(ByHintKind.PARAMETER, ByInlayHints.kindOf(hint, ByInlayHints.labelOf(hint)))
    }

    // endregion

    // region: tooltip

    @Test
    fun `a string tooltip is taken as-is`() {
        val hint = textHint(": int").also { it.setTooltip("builtins.int") }
        assertEquals("builtins.int", ByInlayHints.tooltipOf(hint))
    }

    @Test
    fun `a markup tooltip is taken by its value`() {
        val hint = textHint(": int").also { it.setTooltip(MarkupContent("markdown", "`builtins.int`")) }
        assertEquals("`builtins.int`", ByInlayHints.tooltipOf(hint))
    }

    @Test
    fun `no tooltip and a blank tooltip are both nothing`() {
        assertNull(ByInlayHints.tooltipOf(textHint(": int")))
        assertNull(ByInlayHints.tooltipOf(textHint(": int").also { it.setTooltip("   ") }))
    }

    // endregion

    // region: truncation

    @Test
    fun `a hint at the limit is drawn in full`() {
        val text = "x".repeat(ByInlayHints.MAX_CHARS)
        assertEquals(text, ByInlayHints.truncate(text))
    }

    @Test
    fun `a longer hint is cut to the limit, ellipsis included`() {
        val cut = ByInlayHints.truncate("x".repeat(ByInlayHints.MAX_CHARS + 40))
        assertEquals(ByInlayHints.MAX_CHARS, cut.length)
        assertTrue(cut.endsWith("…"), "expected an ellipsis, got \"$cut\"")
    }

    // endregion

    // region: modes

    @Test
    fun `each kind reads only its own mode`() {
        val modes = ByHintModes(
            parameter = ByHintMode.ALWAYS,
            type = ByHintMode.ON_PUSH,
            returnType = ByHintMode.NEVER,
            typeArgument = ByHintMode.ON_PUSH,
            modifier = ByHintMode.ALWAYS,
            other = ByHintMode.NEVER,
        )
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind.PARAMETER])
        assertEquals(ByHintMode.ON_PUSH, modes[ByHintKind.TYPE])
        assertEquals(ByHintMode.NEVER, modes[ByHintKind.RETURN_TYPE])
        assertEquals(ByHintMode.ON_PUSH, modes[ByHintKind.TYPE_ARGUMENT])
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind.MODIFIER])
        assertEquals(ByHintMode.NEVER, modes[ByHintKind.OTHER])
    }

    @Test
    fun `nothing is collected only when every kind is off`() {
        assertFalse(ByHintModes.all(ByHintMode.NEVER).anyCollected)
        assertTrue(ByHintModes.all(ByHintMode.ON_PUSH).anyCollected)
        assertTrue(
            ByHintModes.all(ByHintMode.NEVER).copy(other = ByHintMode.ALWAYS).anyCollected,
            "one kind left on is a reason to ask the server",
        )
    }

    // endregion

    // region: anchoring

    @Test
    fun `the hints that introduce what follows them anchor forwards, the rest backwards`() {
        // `name=` introduces its argument and `override ` its declaration; the others finish the
        // code they sit after.
        assertFalse(ByInlayHints.relatesToPrecedingText(ByHintKind.PARAMETER))
        assertFalse(ByInlayHints.relatesToPrecedingText(ByHintKind.MODIFIER))
        assertTrue(ByInlayHints.relatesToPrecedingText(ByHintKind.TYPE))
        assertTrue(ByInlayHints.relatesToPrecedingText(ByHintKind.RETURN_TYPE))
        assertTrue(ByInlayHints.relatesToPrecedingText(ByHintKind.TYPE_ARGUMENT))
        assertTrue(ByInlayHints.relatesToPrecedingText(ByHintKind.OTHER))
    }

    // endregion
}
