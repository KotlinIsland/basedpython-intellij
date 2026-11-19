package dev.basedpython.pycharm.lsp.inlay

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.eclipse.lsp4j.Command
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
        for (kind in TEST_HINT_KINDS) {
            assertEquals(kind, ByInlayHints.kindOf(tagged(kind.setting)), kind.setting)
        }
    }

    /**
     * Labels as `by` `main` writes them, measured: a revealed type is the bare type, with the
     * `  revealed: ` the plugin once looked for long gone, so every revealed type was filed under
     * "Other hints". The kind is the tag's, whatever the label happens to look like.
     */
    @Test
    fun `the label plays no part in which kind a hint is`() {
        assertEquals(ByHintKind("revealedTypes"), ByInlayHints.kindOf(tagged("revealedTypes", "list[int]")))
        assertEquals(ByHintKind("revealedTypes"), ByInlayHints.kindOf(tagged("revealedTypes", "1")))
        assertEquals(ByHintKind("callTypeArguments"), ByInlayHints.kindOf(tagged("callTypeArguments", "[int]")))
        assertEquals(ByHintKind("returnTypes"), ByInlayHints.kindOf(tagged("returnTypes", "-> int")))
        assertEquals(ByHintKind("enumValues"), ByInlayHints.kindOf(tagged("enumValues", "1")))
        assertEquals(ByHintKind("inheritedParameterDefaults"), ByInlayHints.kindOf(tagged("inheritedParameterDefaults", "=1")))
        assertEquals(ByHintKind("propertyTypes"), ByInlayHints.kindOf(tagged("propertyTypes", ": int")))
    }

    @Test
    fun `an untagged hint is untagged, whatever its LSP kind or label`() {
        val untagged = listOf(
            textHint("  revealed: int", InlayHintKind.Type),
            textHint(": int", InlayHintKind.Type),
            textHint("x=", InlayHintKind.Parameter),
            textHint("override", InlayHintKind.Type),
            textHint("raises ValueError"),
        )
        for (hint in untagged) {
            assertEquals(ByHintKind.UNTAGGED, ByInlayHints.kindOf(hint), ByInlayHints.labelOf(hint))
        }
    }

    @Test
    fun `a kind this plugin has never heard of is still that kind, and a tag that is not a name is untagged`() {
        // the kinds are by's: one it added after this plugin was built is configurable by its name
        assertEquals(ByHintKind("borrowChecks"), ByInlayHints.kindOf(tagged("borrowChecks")))
        assertEquals(ByHintKind.UNTAGGED, ByInlayHints.kindOf(tagged(JsonPrimitive(3))))
        assertEquals(ByHintKind.UNTAGGED, ByInlayHints.kindOf(tagged(null)))
        assertEquals(ByHintKind.UNTAGGED, ByInlayHints.kindOf(textHint("int").also { it.data = JsonPrimitive("revealedTypes") }))
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
                "variableTypes" to ByHintMode.ON_PUSH,
                "callArgumentNames" to ByHintMode.ALWAYS,
                "inferredRaises" to ByHintMode.NEVER,
            ),
        )
        assertEquals(ByHintMode.ON_PUSH, modes[ByHintKind("variableTypes")])
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind("callArgumentNames")])
        assertEquals(ByHintMode.NEVER, modes[ByHintKind("inferredRaises")])
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind("enumValues")], "an unset kind is on, as by has it")
        assertEquals(ByHintMode.ALWAYS, modes[ByHintKind.UNTAGGED], "a hint from a by without tags is on")
    }

    @Test
    fun `nothing is collected only when every kind by has is off`() {
        assertFalse(ByHintModes.all(ByHintMode.NEVER, TEST_HINT_SETTINGS).anyCollected)
        assertTrue(ByHintModes.all(ByHintMode.ON_PUSH, TEST_HINT_SETTINGS).anyCollected)
        val oneLeft = ByHintModes(TEST_HINT_SETTINGS.drop(1).associateWith { ByHintMode.NEVER }, TEST_HINT_SETTINGS)
        assertTrue(oneLeft.anyCollected, "a kind with no mode of its own is on")
        assertTrue(ByHintModes(TEST_HINT_SETTINGS.associateWith { ByHintMode.NEVER }).anyCollected, "with no catalog, which kinds by has is unknown")
    }

    @Test
    fun `the server is asked to skip exactly the kinds set to never`() {
        val modes = ByHintModes(
            TEST_HINT_SETTINGS.associateWith { if (it == "revealedTypes") ByHintMode.NEVER else ByHintMode.ON_PUSH },
            TEST_HINT_SETTINGS,
        )
        val options = modes.serverOptions()
        assertEquals(false, options["revealedTypes"])
        assertEquals(true, options["variableTypes"], "on push is still computed")
        assertFalse(options.containsKey(""), "an untagged hint has no option")
    }

    // endregion

    // region: anchoring

    @Test
    fun `a hint anchors forwards when by says it introduces what follows it, and backwards otherwise`() {
        assertFalse(ByHintKindInfo(setting = "callArgumentNames", attachesTo = "following").relatesToPrecedingText)
        assertTrue(ByHintKindInfo(setting = "returnTypes", attachesTo = "preceding").relatesToPrecedingText)
        assertTrue(ByHintKindInfo(setting = "variableTypes").relatesToPrecedingText, "unsaid is the common case")
    }

    @Test
    fun `the catalog lists only the kinds a source file has`() {
        val catalog = ByInlayHintCatalog(
            kinds = listOf(
                ByHintKindInfo(setting = "returnTypes", fileKind = "source"),
                ByHintKindInfo(setting = "resolvedTemplates", fileKind = "template"),
                ByHintKindInfo(setting = "variableTypes"),
            ),
        )
        assertEquals(listOf("returnTypes", "variableTypes"), catalog.sourceKinds.map { it.setting })
    }

    /** As `by/inlayHintCatalog` writes a kind whose hints come from a stub, through lsp4j's Gson as the request reads it. */
    @Test
    fun `a catalog entry carries the stub its example's hints were computed against`() {
        val gson = org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler(emptyMap()).gson
        val json = """{"kinds":[{"setting":"stubOnlyMembers","title":"Stub-only members","group":"stubs",""" +
            """"example":{"source":"class A:\n    def f(self, a): ...\n","hints":[{"position":{"line":1,"character":31},""" +
            """"label":"\n    def g(self, a: int) -> None: ...","data":{"kind":"stubOnlyMembers","placement":"below"}}]},""" +
            """"exampleStub":"class A:\n    def f(self, a: int) -> None: ...\n    def g(self, a: int) -> None: ...\n"},""" +
            """{"setting":"variableTypes"}]}"""
        val catalog = gson.fromJson(json, ByInlayHintCatalog::class.java)
        val stubOnly = catalog.kind(ByHintKind("stubOnlyMembers"))!!
        assertEquals("class A:\n    def f(self, a: int) -> None: ...\n    def g(self, a: int) -> None: ...\n", stubOnly.exampleStub)
        assertEquals(ByHintPlacement.BELOW, ByInlayHints.placementOf(stubOnly.example!!.hints!!.single()))
        assertNull(catalog.kind(ByHintKind("variableTypes"))!!.exampleStub)
    }

    // endregion

    // region: placement

    private fun placed(placement: JsonElement?, kind: String = "stubOnlyMembers"): InlayHint =
        InlayHint(Position(0, 0), Either.forLeft("    def g(self) -> None: ...")).also { hint ->
            hint.data = JsonObject().apply {
                addProperty("kind", kind)
                if (placement != null) add("placement", placement)
            }
        }

    @Test
    fun `a hint placed above or below is lines of its own`() {
        assertEquals(ByHintPlacement.ABOVE, ByInlayHints.placementOf(placed(JsonPrimitive("above"))))
        assertEquals(ByHintPlacement.BELOW, ByInlayHints.placementOf(placed(JsonPrimitive("below"))))
        assertTrue(ByInlayHints.placementOf(placed(JsonPrimitive("below"))).isBlock)
    }

    /** By the placement, not the kind: any kind `by` places on lines of its own is drawn there. */
    @Test
    fun `the placement is the hint's, whatever its kind`() {
        assertEquals(ByHintPlacement.BELOW, ByInlayHints.placementOf(placed(JsonPrimitive("below"), kind = "someKindFromLater")))
        assertEquals(ByHintPlacement.INLINE, ByInlayHints.placementOf(placed(null)))
    }

    @Test
    fun `no data, a placement not known, or one not a string is in the line`() {
        assertEquals(ByHintPlacement.INLINE, ByInlayHints.placementOf(textHint(": int")))
        assertEquals(ByHintPlacement.INLINE, ByInlayHints.placementOf(placed(JsonPrimitive("beside"))))
        assertEquals(ByHintPlacement.INLINE, ByInlayHints.placementOf(placed(JsonPrimitive(1))))
        assertFalse(ByHintPlacement.INLINE.isBlock)
    }

    // endregion

    // region: part tooltips, commands and spans

    @Test
    fun `a part's tooltip and command are kept, a blank command is none`() {
        val parts = ByInlayHints.partsOf(
            hint(
                Either.forRight(
                    listOf(
                        InlayHintLabelPart("override").also {
                            it.location = location(3)
                            it.setTooltip(MarkupContent("markdown", "Overrides `Animal.speak`"))
                            it.command = Command("Show overrides", "by.showOverrides")
                        },
                        InlayHintLabelPart(" ").also { it.command = Command("", "") },
                    ),
                ),
                null,
            ),
        )
        assertEquals(ByTip("Overrides `Animal.speak`", markdown = true), parts[0].tip)
        assertEquals("by.showOverrides", parts[0].command?.command)
        assertNull(parts[1].command)
    }

    @Test
    fun `markup that is not markdown is plain text, and so is a string`() {
        assertEquals(ByTip("a < b", markdown = false), ByInlayHints.tipOf(Either.forRight(MarkupContent("plaintext", "a < b"))))
        assertEquals(ByTip("a < b", markdown = false), ByInlayHints.tipOf(Either.forLeft("a < b")))
        assertNull(ByInlayHints.tipOf(Either.forLeft("  ")))
    }

    @Test
    fun `spans cover the parts with a place, a tooltip or a command, cut where the text is cut`() {
        val parts = listOf(
            ByHintPart(": ", null),
            ByHintPart("aaaa", null, ByTip("a", markdown = false)),
            ByHintPart("[", null),
            ByHintPart("b".repeat(80), location(2)),
            ByHintPart("]", location(3)),
        )
        val label = parts.joinToString("") { it.text }
        assertEquals(listOf(2 to 6, 7 to 87, 87 to 88), ByInlayHints.spansOf(parts, label).map { it.start to it.end })
        val drawn = ByInlayHints.truncate(label)
        // the ellipsis stands for what it replaced: the long part is cut to what is drawn, `]` is gone
        assertEquals(listOf(2 to 6, 7 to drawn.length - 1), ByInlayHints.spansOf(parts, drawn).map { it.start to it.end })
    }

    // endregion
}
