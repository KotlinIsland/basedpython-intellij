package dev.basedpython.pycharm.lsp.inlay

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.InlayHint
import org.eclipse.lsp4j.InlayHintKind
import org.eclipse.lsp4j.InlayHintLabelPart
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
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
        // `by` asks for the gap after `override` with `paddingRight` these days, but a label that
        // carries its own space is drawn as sent: trimming this one would render `overridedef`.
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

    // region: links

    private fun location(line: Int) = Location("file:///p/a.by", Range(Position(line, 0), Position(line, 4)))

    /** `: list[int]` as `by` sends it: the names carry where they are declared, the brackets do not. */
    private val listOfInt = listOf(
        InlayHintLabelPart(": "),
        InlayHintLabelPart("list").also { it.location = location(1) },
        InlayHintLabelPart("["),
        InlayHintLabelPart("int").also { it.location = location(2) },
        InlayHintLabelPart("]"),
    )

    @Test
    fun `each part keeps the place it names`() {
        assertEquals(
            listOf(
                ByHintPart(": ", null),
                ByHintPart("list", location(1)),
                ByHintPart("[", null),
                ByHintPart("int", location(2)),
                ByHintPart("]", null),
            ),
            ByInlayHints.partsOf(hint(Either.forRight(listOfInt))),
        )
    }

    @Test
    fun `a string label is one part naming nothing`() {
        assertEquals(listOf(ByHintPart(": int", null)), ByInlayHints.partsOf(textHint(": int")))
    }

    @Test
    fun `the links are the named parts' character ranges in the drawn text`() {
        val parts = ByInlayHints.partsOf(hint(Either.forRight(listOfInt)))
        assertEquals(
            listOf(ByHintLink(2, 6, location(1)), ByHintLink(7, 10, location(2))),
            ByInlayHints.linksOf(parts, ": list[int]"),
        )
    }

    @Test
    fun `a cut shortens the link it reaches and drops the ones it removes`() {
        val parts = ByInlayHints.partsOf(hint(Either.forRight(listOfInt)))
        // `: li…` — the ellipsis stands for the rest and goes nowhere
        val drawn = ByInlayHints.truncate(": list[int]", max = 5)
        assertEquals(": li…", drawn)
        assertEquals(listOf(ByHintLink(2, 4, location(1))), ByInlayHints.linksOf(parts, drawn))
    }

    // endregion

    // region: kind

    private fun tagged(tag: JsonElement?, text: String = "int"): InlayHint =
        textHint(text, InlayHintKind.Type).also { hint ->
            hint.data = JsonObject().apply { if (tag != null) add("kind", tag) }
        }

    private fun tagged(option: String, text: String = "int"): InlayHint = tagged(JsonPrimitive(option), text)

    @Test
    fun `a hint is filed under the option by tags it with`() {
        for (kind in ByHintKind.entries.filter { it.option != null }) {
            assertEquals(kind, ByInlayHints.kindOf(tagged(kind.option!!)), kind.option)
        }
    }

    /**
     * Labels as `by` `main` writes them, measured: a revealed type is the bare type, with the
     * `  revealed: ` the plugin once looked for long gone, so every revealed type was filed under
     * "Other hints". The kind is the tag's, whatever the label happens to look like.
     */
    @Test
    fun `the label plays no part in which kind a hint is`() {
        assertEquals(ByHintKind.REVEALED_TYPES, ByInlayHints.kindOf(tagged("revealedTypes", "list[int]")))
        assertEquals(ByHintKind.REVEALED_TYPES, ByInlayHints.kindOf(tagged("revealedTypes", "1")))
        assertEquals(ByHintKind.CALL_TYPE_ARGUMENTS, ByInlayHints.kindOf(tagged("callTypeArguments", "[int]")))
        assertEquals(ByHintKind.INFERRED_RETURN_TYPES, ByInlayHints.kindOf(tagged("inferredReturnTypes", "-> int")))
        assertEquals(ByHintKind.ENUM_VALUES, ByInlayHints.kindOf(tagged("enumValues", "1")))
        assertEquals(ByHintKind.INHERITED_PARAMETER_DEFAULTS, ByInlayHints.kindOf(tagged("inheritedParameterDefaults", "=1")))
        assertEquals(ByHintKind.PROPERTY_TYPES, ByInlayHints.kindOf(tagged("propertyTypes", ": int")))
    }

    @Test
    fun `an untagged hint is other, whatever its LSP kind or label`() {
        val untagged = listOf(
            textHint("  revealed: int", InlayHintKind.Type),
            textHint(": int", InlayHintKind.Type),
            textHint("x=", InlayHintKind.Parameter),
            textHint("override", InlayHintKind.Type),
            textHint("raises ValueError"),
        )
        for (hint in untagged) {
            assertEquals(ByHintKind.OTHER, ByInlayHints.kindOf(hint), ByInlayHints.labelOf(hint))
        }
    }

    @Test
    fun `a kind from a newer by, or a tag that is not a name, is other`() {
        assertEquals(ByHintKind.OTHER, ByInlayHints.kindOf(tagged("borrowChecks")))
        assertEquals(ByHintKind.OTHER, ByInlayHints.kindOf(tagged(JsonPrimitive(3))))
        assertEquals(ByHintKind.OTHER, ByInlayHints.kindOf(tagged(null)))
        assertEquals(ByHintKind.OTHER, ByInlayHints.kindOf(textHint("int").also { it.data = JsonPrimitive("revealedTypes") }))
    }

    @Test
    fun `other is the only kind no by option names`() {
        assertEquals(listOf(ByHintKind.OTHER), ByHintKind.entries.filter { it.option == null })
        assertEquals(ByHintKind.OTHER, ByHintKind.ofOption("other"), "the catch-all's settings key is not a tag")
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
        val forwards = setOf(
            ByHintKind.CALL_ARGUMENT_NAMES,
            ByHintKind.IMPLICIT_PARAMETERS,
            ByHintKind.IMPLICIT_SELF,
            ByHintKind.INFERRED_OVERRIDE,
            ByHintKind.INFERRED_VARIANCE,
            ByHintKind.INFERRED_REIFICATION,
            ByHintKind.PARAMETER_STABILITY,
        )
        for (kind in ByHintKind.entries) {
            assertEquals(kind !in forwards, kind.relatesToPrecedingText, kind.name)
        }
    }

    // endregion
}
