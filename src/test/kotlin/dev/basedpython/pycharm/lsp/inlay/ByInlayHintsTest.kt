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

    // region: shape

    private fun shapeOf(text: String, kind: InlayHintKind? = null): ByHintShape {
        val hint = textHint(text, kind)
        return ByInlayHints.shapeOf(hint, ByInlayHints.labelOf(hint))
    }

    /**
     * Every label `by` writes, copied from the constructors in `ty_ide::InlayHint` that write them,
     * with the LSP kind each one is sent under.
     *
     * This is the table the whole classification rests on: the protocol carries two kinds for the
     * fourteen things the server distinguishes, and the rest is recovered from these shapes.
     */
    @Test
    fun `every label by writes is recognised as the kind that wrote it`() {
        val cases = listOf(
            Triple(": int", InlayHintKind.Type, ByHintShape.TYPE),
            Triple(": list[int]", InlayHintKind.Type, ByHintShape.TYPE),
            Triple("[int]", InlayHintKind.Type, ByHintShape.TYPE_ARGUMENTS),
            Triple("[T=int, U=str]", InlayHintKind.Type, ByHintShape.TYPE_ARGUMENTS),
            Triple("T=", InlayHintKind.Type, ByHintShape.TYPE_ARGUMENT_NAME),
            Triple(" | int", InlayHintKind.Type, ByHintShape.NUMERIC_PROMOTION),
            Triple(" | float | int", InlayHintKind.Type, ByHintShape.NUMERIC_PROMOTION),
            Triple("  revealed: int", InlayHintKind.Type, ByHintShape.REVEALED_TYPE),
            Triple(" raises ValueError", InlayHintKind.Type, ByHintShape.RAISES),
            Triple("override ", InlayHintKind.Type, ByHintShape.OVERRIDE),
            Triple("reified ", InlayHintKind.Type, ByHintShape.REIFICATION),
            Triple("out ", InlayHintKind.Type, ByHintShape.VARIANCE),
            Triple("in ", InlayHintKind.Type, ByHintShape.VARIANCE),
            Triple("in out ", InlayHintKind.Type, ByHintShape.VARIANCE),
            Triple("x=", InlayHintKind.Parameter, ByHintShape.ARGUMENT_NAME),
            Triple("self", InlayHintKind.Parameter, ByHintShape.IMPLICIT_PARAMETER),
            Triple("it: int", InlayHintKind.Parameter, ByHintShape.IMPLICIT_PARAMETER),
            Triple("self, it: int", InlayHintKind.Parameter, ByHintShape.IMPLICIT_PARAMETER),
            Triple(", ctx=my_context", InlayHintKind.Parameter, ByHintShape.IMPLICIT_ARGUMENT),
        )
        for ((label, kind, expected) in cases) {
            assertEquals(expected, shapeOf(label, kind), "\"$label\" as $kind")
        }
    }

    @Test
    fun `the LSP kind is what splits an argument's name from a type argument's`() {
        // `t=` and `T=` are the same characters standing for different things, and the kind `by`
        // sends them under is the only thing that says which.
        assertEquals(ByHintShape.ARGUMENT_NAME, shapeOf("t=", InlayHintKind.Parameter))
        assertEquals(ByHintShape.TYPE_ARGUMENT_NAME, shapeOf("T=", InlayHintKind.Type))
    }

    @Test
    fun `a shape from a newer by is its own kind, not the nearest one`() {
        for (label in listOf("=> 3", "«borrowed»", "-> bool")) {
            assertEquals(
                ByHintShape.UNKNOWN,
                shapeOf(label, InlayHintKind.Type),
                "\"$label\" should fall to the kind that has a setting for anything else",
            )
        }
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
            mapOf(
                ByHintKind.VARIABLE_TYPES to ByHintMode.ON_PUSH,
                ByHintKind.CALL_ARGUMENT_NAMES to ByHintMode.ALWAYS,
                ByHintKind.INFERRED_RAISES to ByHintMode.NEVER,
            ),
        )
        assertEquals(ByHintMode.ON_PUSH, modes[ByHintKind.VARIABLE_TYPES])
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind.CALL_ARGUMENT_NAMES])
        assertEquals(ByHintMode.NEVER, modes[ByHintKind.INFERRED_RAISES])
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind.OTHER], "an unset kind is on")
    }

    @Test
    fun `a shape takes the mode of the kind that wrote it`() {
        val modes = ByHintModes.all(ByHintMode.NEVER).let {
            ByHintModes(ByHintKind.entries.associateWith { kind ->
                if (kind == ByHintKind.INFERRED_OVERRIDE) ByHintMode.ON_PUSH else it[kind]
            })
        }
        assertEquals(ByHintMode.ON_PUSH, modes.forShape(ByHintShape.OVERRIDE))
        assertEquals(ByHintMode.NEVER, modes.forShape(ByHintShape.TYPE))
    }

    @Test
    fun `where two kinds are written alike, the more visible setting wins`() {
        // `by` writes a variable's type and a lambda parameter's identically, so a hint of that
        // shape cannot be attributed to one of them. Showing it always is the answer that never
        // hides a hint someone asked to see.
        val modes = ByHintModes(
            mapOf(
                ByHintKind.VARIABLE_TYPES to ByHintMode.ALWAYS,
                ByHintKind.LAMBDA_PARAMETER_TYPES to ByHintMode.ON_PUSH,
            ),
        )
        assertEquals(ByHintMode.ALWAYS, modes.forShape(ByHintShape.TYPE))
    }

    @Test
    fun `nothing is collected only when every kind is off`() {
        assertFalse(ByHintModes.all(ByHintMode.NEVER).anyCollected)
        assertTrue(ByHintModes.all(ByHintMode.ON_PUSH).anyCollected)
    }

    @Test
    fun `the server is asked to skip exactly the kinds set to never`() {
        val modes = ByHintModes(
            ByHintKind.entries.associateWith {
                if (it == ByHintKind.REVEALED_TYPES) ByHintMode.NEVER else ByHintMode.ON_PUSH
            },
        )
        val options = modes.serverOptions()
        assertEquals(false, options["revealedTypes"])
        assertEquals(true, options["variableTypes"], "on push is still computed")
        assertFalse(options.containsKey("other"), "the catch-all is the plugin's, not the server's")
    }

    // endregion

    // region: anchoring

    @Test
    fun `the hints that introduce what follows them anchor forwards, the rest backwards`() {
        assertFalse(ByHintShape.ARGUMENT_NAME.relatesToPrecedingText)
        assertFalse(ByHintShape.IMPLICIT_PARAMETER.relatesToPrecedingText)
        assertFalse(ByHintShape.OVERRIDE.relatesToPrecedingText)
        assertFalse(ByHintShape.VARIANCE.relatesToPrecedingText)
        assertFalse(ByHintShape.REIFICATION.relatesToPrecedingText)
        assertTrue(ByHintShape.TYPE.relatesToPrecedingText)
        assertTrue(ByHintShape.TYPE_ARGUMENTS.relatesToPrecedingText)
        assertTrue(ByHintShape.RAISES.relatesToPrecedingText)
        assertTrue(ByHintShape.UNKNOWN.relatesToPrecedingText)
    }

    // endregion
}
