package dev.basedpython.pycharm.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a string literal's content starts and stops, which is the question every reader of a
 * literal asks first — escape colouring, f-string interpolation, and language injection, which is
 * the one that cannot be a little bit wrong: the content range *is* the injected document.
 */
class StringLiteralShapeTest {

    private fun content(raw: String): String? =
        StringLiteralShape.of(raw)?.let { raw.substring(it.contentStart, it.contentEnd) }

    // region: quotes and prefixes

    @Test
    fun `the content of a plain literal is what is between the quotes`() {
        assertEquals("hi", content("\"hi\""))
        assertEquals("hi", content("'hi'"))
    }

    @Test
    fun `an empty literal has empty content rather than no shape`() {
        assertEquals("", content("\"\""))
        assertEquals(0, StringLiteralShape.of("\"\"")?.contentLength)
    }

    @Test
    fun `triple quotes are three characters at each end`() {
        assertEquals("hi", content("\"\"\"hi\"\"\""))
        assertEquals("", content("\"\"\"\"\"\""))
        assertTrue(StringLiteralShape.of("\"\"\"hi\"\"\"")?.isTriple == true)
    }

    @Test
    fun `a prefix is not content`() {
        assertEquals("hi", content("r\"hi\""))
        assertEquals("hi", content("rb\"hi\""))
        assertEquals("{x}", content("f\"{x}\""))
        assertEquals("hi", content("R\"hi\""))
    }

    @Test
    fun `what the prefix declares is read off it, whatever case it is written in`() {
        val shape = StringLiteralShape.of("Rb'x'")
        assertTrue(shape?.isRaw == true)
        assertTrue(shape?.isBytes == true)
        assertFalse(shape?.isFString == true)
    }

    @Test
    fun `something that is not a literal has no shape`() {
        assertNull(StringLiteralShape.of(""))
        assertNull(StringLiteralShape.of("name"))
        assertNull(StringLiteralShape.of("rb"))
    }

    // endregion

    // region: termination

    @Test
    fun `a literal the lexer stopped at the end of a line runs to the end of the token`() {
        val shape = StringLiteralShape.of("\"hi")
        assertFalse(shape!!.isTerminated)
        assertEquals("hi", content("\"hi"))
    }

    @Test
    fun `a literal ending in an escaped quote is not terminated by it`() {
        // `"a\"` — the last character is a quote, and it is content.
        assertFalse(StringLiteralShape.of("\"a\\\"")!!.isTerminated)
        // `"a\\"` — the backslash is escaped, so the quote closes.
        assertTrue(StringLiteralShape.of("\"a\\\\\"")!!.isTerminated)
    }

    @Test
    fun `the same rule holds three quotes in`() {
        // `"""a\"""` — the escape eats the first of the closing three.
        assertFalse(StringLiteralShape.of("\"\"\"a\\\"\"\"")!!.isTerminated)
        // `"""a\""""` — one quote is content and the last three close.
        val closed = StringLiteralShape.of("\"\"\"a\\\"\"\"\"")
        assertTrue(closed!!.isTerminated)
        assertEquals("a\\\"", content("\"\"\"a\\\"\"\"\""))
    }

    @Test
    fun `a lone opening triple quote is not a closed empty literal`() {
        val shape = StringLiteralShape.of("\"\"\"")
        assertTrue(shape!!.isTriple)
        assertFalse(shape.isTerminated)
        assertEquals(0, shape.contentLength)
    }

    // endregion
}
