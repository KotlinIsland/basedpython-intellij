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

    // region: toggles

    @Test
    fun `each toggle switches only its own kind`() {
        assertTrue(ByInlayHints.isEnabled(ByHintKind.PARAMETER, parameters = true, types = false, returns = false))
        assertFalse(ByInlayHints.isEnabled(ByHintKind.TYPE, parameters = true, types = false, returns = false))
        assertFalse(ByInlayHints.isEnabled(ByHintKind.RETURN_TYPE, parameters = true, types = false, returns = false))

        assertTrue(ByInlayHints.isEnabled(ByHintKind.TYPE, parameters = false, types = true, returns = false))
        assertTrue(ByInlayHints.isEnabled(ByHintKind.RETURN_TYPE, parameters = false, types = false, returns = true))
    }

    // endregion

    // region: anchoring

    @Test
    fun `a parameter hint introduces the text after it, every other hint completes the text before`() {
        assertFalse(ByInlayHints.relatesToPrecedingText(ByHintKind.PARAMETER))
        assertTrue(ByInlayHints.relatesToPrecedingText(ByHintKind.TYPE))
        assertTrue(ByInlayHints.relatesToPrecedingText(ByHintKind.RETURN_TYPE))
    }

    // endregion
}
