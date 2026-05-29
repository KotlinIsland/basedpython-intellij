package dev.basedpython.pycharm.highlight.fstring

import com.intellij.openapi.util.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive pure-logic tests for [FStringInterpolation]. No IDE fixture required.
 *
 * Offsets in expected [TextRange]s are RELATIVE to the start of the literal text (index 0 is
 * the first prefix/quote char).
 */
class FStringInterpolationTest {

    private fun ranges(raw: String): List<TextRange> = FStringInterpolation.interpolationRanges(raw)

    private fun range(start: Int, end: Int) = TextRange(start, end)

    /** Helper: asserts the produced ranges equal the given (start,end) pairs in order. */
    private fun assertRanges(raw: String, vararg expected: TextRange) {
        assertEquals("ranges for <$raw>", expected.toList(), ranges(raw))
    }

    // ---------------------------------------------------------------------
    // Prefix / f-string detection
    // ---------------------------------------------------------------------

    @Test
    fun plainStringIsNotFString() {
        assertFalse(FStringInterpolation.isFString("\"hello\""))
    }

    @Test
    fun plainStringYieldsNoRanges() {
        assertRanges("\"{x}\"")
    }

    @Test
    fun lowercaseFIsFString() {
        assertTrue(FStringInterpolation.isFString("f\"x\""))
    }

    @Test
    fun uppercaseFIsFString() {
        assertTrue(FStringInterpolation.isFString("F\"x\""))
    }

    @Test
    fun rfPrefixIsFString() {
        assertTrue(FStringInterpolation.isFString("rf\"x\""))
    }

    @Test
    fun frPrefixIsFString() {
        assertTrue(FStringInterpolation.isFString("fr\"x\""))
    }

    @Test
    fun capitalRfPrefixIsFString() {
        assertTrue(FStringInterpolation.isFString("Rf\"x\""))
    }

    @Test
    fun bytesPrefixIsNotFString() {
        assertFalse(FStringInterpolation.isFString("b\"x\""))
    }

    @Test
    fun rawNonFPrefixIsNotFString() {
        assertFalse(FStringInterpolation.isFString("r\"x\""))
    }

    @Test
    fun analyzePlainStringReportsNotFString() {
        val a = FStringInterpolation.analyze("\"{x}\"")
        assertFalse(a.isFString)
        assertTrue(a.ranges.isEmpty())
    }

    @Test
    fun analyzeFStringReportsIsFString() {
        val a = FStringInterpolation.analyze("f\"{x}\"")
        assertTrue(a.isFString)
        assertEquals(listOf(range(2, 5)), a.ranges)
    }

    // ---------------------------------------------------------------------
    // Simple interpolations
    // ---------------------------------------------------------------------

    @Test
    fun simpleSingleInterpolation() {
        // f"{x}"  -> indices: 0:f 1:" 2:{ 3:x 4:} 5:"
        assertRanges("f\"{x}\"", range(2, 5))
    }

    @Test
    fun interpolationWithLeadingText() {
        // f"hi {x}" -> { at index 5, } at index 7 -> range(5,8)
        assertRanges("f\"hi {x}\"", range(5, 8))
    }

    @Test
    fun interpolationWithTrailingText() {
        // f"{x} hi" -> { at 2, } at 4 -> range(2,5)
        assertRanges("f\"{x} hi\"", range(2, 5))
    }

    @Test
    fun multipleInterpolations() {
        // f"{a}{b}" -> {a} at 2..5, {b} at 5..8
        assertRanges("f\"{a}{b}\"", range(2, 5), range(5, 8))
    }

    @Test
    fun multipleInterpolationsWithGap() {
        // f"{a} {b}" -> {a} at 2..5, {b} at 6..9
        assertRanges("f\"{a} {b}\"", range(2, 5), range(6, 9))
    }

    @Test
    fun adjacentCloseOpenBraces() {
        // f"{a}{b}" adjacency }{ — verify boundary handling
        val rs = ranges("f\"{a}{b}\"")
        assertEquals(2, rs.size)
        assertEquals(range(2, 5), rs[0])
        assertEquals(range(5, 8), rs[1])
    }

    @Test
    fun singleQuotedFString() {
        // f'{x}' -> same offsets as double-quoted
        assertRanges("f'{x}'", range(2, 5))
    }

    @Test
    fun longerExpressionInsideBraces() {
        // f"{a + b}" -> { at 2, } at 8 -> range(2,9)
        assertRanges("f\"{a + b}\"", range(2, 9))
    }

    // ---------------------------------------------------------------------
    // Escaped braces
    // ---------------------------------------------------------------------

    @Test
    fun escapedOpenBracesAreNotInterpolation() {
        assertRanges("f\"{{\"")
    }

    @Test
    fun escapedCloseBracesAreNotInterpolation() {
        assertRanges("f\"}}\"")
    }

    @Test
    fun escapedBracesAroundLiteralText() {
        // f"{{x}}" -> all escaped, no interpolation
        assertRanges("f\"{{x}}\"")
    }

    @Test
    fun escapedThenRealInterpolation() {
        // f"{{ {x}" -> indices: 0:f 1:" 2:{ 3:{ 4:space 5:{ 6:x 7:} 8:"
        // {{ escaped, then {x} is real at 5..8
        assertRanges("f\"{{ {x}\"", range(5, 8))
    }

    @Test
    fun realInterpolationThenEscaped() {
        // f"{x} }}" -> {x} at 2..5, }} escaped
        assertRanges("f\"{x} }}\"", range(2, 5))
    }

    // ---------------------------------------------------------------------
    // Nested braces
    // ---------------------------------------------------------------------

    @Test
    fun nestedDictLiteral() {
        // f"{ {1:2} }" -> outer { at 2 ... matching } at 9
        // indices: 0:f 1:" 2:{ 3:sp 4:{ 5:1 6:: 7:2 8:} 9:sp 10:} 11:"
        assertRanges("f\"{ {1:2} }\"", range(2, 11))
    }

    @Test
    fun subscriptWithQuotes() {
        // f"{d['k']}" -> single { ... } region; quotes inside don't break balance
        // indices: 0:f 1:" 2:{ 3:d 4:[ 5:' 6:k 7:' 8:] 9:} 10:"
        assertRanges("f\"{d['k']}\"", range(2, 10))
    }

    @Test
    fun deeplyNestedBraces() {
        // f"{ {{}} }" — careful: inside an interpolation, {{ and }} are still depth chars
        // indices: 0:f 1:" 2:{ 3:sp 4:{ 5:{ 6:} 7:} 8:sp 9:} 10:"
        // depth: at 2 ->1, 4 ->2, 5 ->3, 6 ->2, 7 ->1, 9 ->0 => range(2,10)
        assertRanges("f\"{ {{}} }\"", range(2, 10))
    }

    // ---------------------------------------------------------------------
    // Format specs / conversions
    // ---------------------------------------------------------------------

    @Test
    fun formatSpecIncludedInRange() {
        // f"{x:>10}" -> whole {...} including spec
        // indices: 0:f 1:" 2:{ 3:x 4:: 5:> 6:1 7:0 8:} 9:"
        assertRanges("f\"{x:>10}\"", range(2, 9))
    }

    @Test
    fun nestedFormatSpec() {
        // f"{x:>{w}}" -> outer braces balance the nested {w}
        // indices: 0:f 1:" 2:{ 3:x 4:: 5:> 6:{ 7:w 8:} 9:} 10:"
        // depth: 2->1, 6->2, 8->1, 9->0 => range(2,10)
        assertRanges("f\"{x:>{w}}\"", range(2, 10))
    }

    @Test
    fun conversionFlag() {
        // f"{x!r}" -> whole region
        // indices: 0:f 1:" 2:{ 3:x 4:! 5:r 6:} 7:"
        assertRanges("f\"{x!r}\"", range(2, 7))
    }

    // ---------------------------------------------------------------------
    // Triple-quoted f-strings
    // ---------------------------------------------------------------------

    @Test
    fun tripleQuotedFString() {
        // f"""{x}""" -> content starts at index 4 (f + 3 quotes)
        // indices: 0:f 1:" 2:" 3:" 4:{ 5:x 6:} 7:" 8:" 9:"
        assertRanges("f\"\"\"{x}\"\"\"", range(4, 7))
    }

    @Test
    fun tripleQuotedSingleQuoteFString() {
        // f'''{x}''' -> same offsets
        assertRanges("f'''{x}'''", range(4, 7))
    }

    @Test
    fun tripleQuotedMultipleInterpolations() {
        // f"""{a}{b}""" -> {a} 4..7, {b} 7..10
        assertRanges("f\"\"\"{a}{b}\"\"\"", range(4, 7), range(7, 10))
    }

    @Test
    fun tripleQuotedWithNewline() {
        // f"""\n{x}\n""" with an actual newline char in content
        val raw = "f\"\"\"\n{x}\n\"\"\""
        // indices: 0:f 1:" 2:" 3:" 4:\n 5:{ 6:x 7:} 8:\n ...
        assertRanges(raw, range(5, 8))
    }

    // ---------------------------------------------------------------------
    // Unterminated braces / strings
    // ---------------------------------------------------------------------

    @Test
    fun unterminatedBraceHighlightsToEnd() {
        // f"{x" (no closing brace, no closing quote present at end as quote)
        // indices: 0:f 1:" 2:{ 3:x  (len 4, no closing quote => contentEnd=4)
        assertRanges("f\"{x", range(2, 4))
    }

    @Test
    fun unterminatedBraceWithClosingQuote() {
        // f"{x" with trailing quote: f"{x"
        // indices: 0:f 1:" 2:{ 3:x 4:" -> closing quote at 4 => contentEnd=4 => range(2,4)
        assertRanges("f\"{x\"", range(2, 4))
    }

    @Test
    fun unterminatedBraceAfterValidOne() {
        // f"{a}{b" -> {a} closed at 2..5, then {b unterminated -> range(5, contentEnd)
        // indices: 0:f 1:" 2:{ 3:a 4:} 5:{ 6:b 7:" -> contentEnd 7
        assertRanges("f\"{a}{b\"", range(2, 5), range(5, 7))
    }

    @Test
    fun emptyInterpolation() {
        // f"{}" -> { at 2, } at 3 -> range(2,4)
        assertRanges("f\"{}\"", range(2, 4))
    }

    @Test
    fun noInterpolationInFString() {
        // f"hello" -> no braces
        assertRanges("f\"hello\"")
    }

    @Test
    fun emptyFString() {
        assertRanges("f\"\"")
    }

    @Test
    fun rawFStringInterpolation() {
        // rf"{x}" -> prefix is 2 chars (r,f), quote at 2, { at 3, } at 5
        // indices: 0:r 1:f 2:" 3:{ 4:x 5:} 6:"
        assertRanges("rf\"{x}\"", range(3, 6))
    }

    @Test
    fun frPrefixOffsets() {
        // fr"{x}" -> same as rf
        assertRanges("fr\"{x}\"", range(3, 6))
    }

    @Test
    fun capitalRfPrefixOffsets() {
        // Rf"{x}" -> prefix R,f -> { at 3
        assertRanges("Rf\"{x}\"", range(3, 6))
    }

    @Test
    fun textBetweenMultipleInterpolationsAndBraces() {
        // f"a{x}b{{c}}d{y}e"
        // indices: 0:f 1:" 2:a 3:{ 4:x 5:} 6:b 7:{ 8:{ 9:c 10:} 11:} 12:d 13:{ 14:y 15:} 16:e 17:"
        // {x} 3..6 ; {{ }} escaped ; {y} 13..16
        assertRanges("f\"a{x}b{{c}}d{y}e\"", range(3, 6), range(13, 16))
    }

    @Test
    fun malformedNoQuoteAfterPrefix() {
        // "f" alone (no quote) -> not analyzable -> no ranges
        assertRanges("f")
    }

    @Test
    fun nestedFunctionCallInInterpolation() {
        // f"{foo(a, b)}" -> single region, parens are just content
        // indices: 0:f 1:" 2:{ 3:f 4:o 5:o 6:( 7:a 8:, 9:sp 10:b 11:) 12:} 13:"
        assertRanges("f\"{foo(a, b)}\"", range(2, 13))
    }
}
