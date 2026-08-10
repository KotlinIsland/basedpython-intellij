package dev.basedpython.pycharm.highlight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Escape-sequence detection inside string literals.
 *
 * This is the one piece of the old whole-file annotator that outlived the move to LSP semantic
 * tokens, because a semantic token covers a whole string literal and never describes what is
 * inside the quotes.
 */
class StringEscapesTest {

    /** The escape sequences found in [raw], as their literal text. */
    private fun escapes(raw: String): List<String> =
        stringEscapeRanges(raw).map { raw.substring(it.startOffset, it.endOffset) }

    @Test
    fun `simple escapes`() {
        assertEquals(listOf("""\n""", """\t"""), escapes(""""a\nb\tc""""))
    }

    @Test
    fun `an escaped backslash does not start a second escape`() {
        assertEquals(listOf("""\\"""), escapes(""""a\\n""""))
    }

    @Test
    fun `hex unicode and named escapes measure their full length`() {
        assertEquals(listOf("""\x41"""), escapes(""""\x41""""))
        assertEquals(listOf("\\u00e9"), escapes("\"\\u00e9\""))
        assertEquals(listOf("""\U0001F600"""), escapes(""""\U0001F600""""))
        assertEquals(listOf("""\N{BULLET}"""), escapes(""""\N{BULLET}""""))
    }

    @Test
    fun `a truncated hex escape falls back to two characters`() {
        assertEquals(listOf("""\x"""), escapes(""""\xZZ""""))
    }

    @Test
    fun `octal escapes take up to three digits`() {
        assertEquals(listOf("""\101"""), escapes(""""\101""""))
        assertEquals(listOf("""\12"""), escapes(""""\12""""))
    }

    @Test
    fun `an unknown escape is still highlighted`() {
        // Python keeps `\q` as backslash-q; colouring it is how the reader notices.
        assertEquals(listOf("""\q"""), escapes(""""\q""""))
    }

    @Test
    fun `raw strings have no escapes`() {
        assertEquals(emptyList<String>(), escapes("""r"a\nb""""))
        assertEquals(emptyList<String>(), escapes("""R"a\nb""""))
        assertEquals(emptyList<String>(), escapes("""rb"a\nb""""))
    }

    @Test
    fun `byte and unicode prefixes still escape`() {
        assertEquals(listOf("""\n"""), escapes("""b"a\nb""""))
        assertEquals(listOf("""\n"""), escapes("""u"a\nb""""))
    }

    @Test
    fun `triple quoted strings`() {
        assertEquals(listOf("""\n"""), escapes("\"\"\"a\\nb\"\"\""))
    }

    @Test
    fun `escapes inside an f-string interpolation are code, not escapes`() {
        // `{...}` is an expression; a backslash in there is not a string escape.
        assertEquals(listOf("""\n"""), escapes("""f"{a}\n""""))
    }

    @Test
    fun `doubled braces in an f-string are not interpolation`() {
        assertEquals(listOf("""\n"""), escapes("""f"{{x}}\n""""))
    }

    @Test
    fun `an unterminated literal still reports its escapes`() {
        // The usual state halfway through typing.
        assertEquals(listOf("""\n"""), escapes(""""a\n"""))
    }

    @Test
    fun `a non-literal yields nothing`() {
        assertEquals(emptyList<String>(), escapes(""))
        assertEquals(emptyList<String>(), escapes("notastring"))
        assertEquals(emptyList<String>(), escapes("\"\""))
    }
}
