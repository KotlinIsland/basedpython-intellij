package dev.basedpython.pycharm.lang

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The text a literal's source stands for, and where each character of it came from.
 *
 * Both halves matter and they fail differently. Get the text wrong and an injected fragment is not
 * the string the program holds — `\"` shows up as a backslash in the middle of the html. Get the
 * map wrong and the text is right but every edit inside the fragment lands a few characters off.
 */
class StringLiteralDecodingTest {

    /** The decoded text of [source], and the source offset each of its characters came from. */
    private fun decode(source: String, isRaw: Boolean = false): Pair<String, List<Int>> {
        val out = StringBuilder()
        val offsets = IntArray(source.length + 1)
        StringLiteralDecoding.decode(source, isRaw, out, offsets)
        return out.toString() to offsets.take(out.length + 1)
    }

    private fun text(source: String, isRaw: Boolean = false): String = decode(source, isRaw).first

    // region: what the text comes out as

    @Test
    fun `text with no escapes in it is itself`() {
        assertEquals("<div>hi</div>", text("<div>hi</div>"))
    }

    @Test
    fun `the escapes python spells with a letter`() {
        assertEquals("\n", text("\\n"))
        assertEquals("\t", text("\\t"))
        assertEquals("\r", text("\\r"))
        assertEquals("\u0007", text("\\a"))
        assertEquals("\u000B", text("\\v"))
        assertEquals("\u000C", text("\\f"))
        assertEquals("\b", text("\\b"))
    }

    @Test
    fun `a quote or a backslash stands for itself`() {
        assertEquals("<a href=\"/\">", text("<a href=\\\"/\\\">"))
        assertEquals("\\", text("\\\\"))
        assertEquals("'", text("\\'"))
    }

    @Test
    fun `numeric escapes`() {
        assertEquals("A", text("\\x41"))
        assertEquals("A", text("\\u0041"))
        assertEquals("A", text("\\101"))
        assertEquals("\u0000", text("\\0"))
        // Above the basic plane, so two chars come out of ten.
        assertEquals("😀", text("\\U0001F600"))
    }

    @Test
    fun `a character named the way unicode names it`() {
        assertEquals("a", text("\\N{LATIN SMALL LETTER A}"))
        // A name nothing has is left exactly as written rather than swallowed.
        assertEquals("\\N{NO SUCH CHARACTER AT ALL}", text("\\N{NO SUCH CHARACTER AT ALL}"))
    }

    @Test
    fun `an escape python does not recognise keeps its backslash`() {
        assertEquals("\\q", text("\\q"))
        // Half-typed, which is what an editor sees most of the time.
        assertEquals("\\x", text("\\x"))
        assertEquals("\\xZZ", text("\\xZZ"))
        assertEquals("\\", text("\\"))
    }

    @Test
    fun `a line continuation is nothing at all`() {
        assertEquals("ab", text("a\\\nb"))
        assertEquals("ab", text("a\\\r\nb"))
    }

    @Test
    fun `a raw literal decodes nothing`() {
        assertEquals("\\d+", text("\\d+", isRaw = true))
        assertEquals("\\n", text("\\n", isRaw = true))
    }

    // endregion

    // region: where each character came from

    @Test
    fun `every character of plain text maps to itself`() {
        val (decoded, offsets) = decode("abc")
        assertEquals("abc", decoded)
        assertEquals(listOf(0, 1, 2, 3), offsets)
    }

    @Test
    fun `a decoded character maps to the start of the escape it came from`() {
        // `a\nb` — the newline stands for two source characters, and `b` is at offset 3.
        val (decoded, offsets) = decode("a\\nb")
        assertEquals("a\nb", decoded)
        assertEquals(listOf(0, 1, 3, 4), offsets)
    }

    @Test
    fun `both halves of a surrogate pair map to the escape that produced them`() {
        val (decoded, offsets) = decode("\\U0001F600!")
        assertEquals("😀!", decoded)
        assertEquals(listOf(0, 0, 10, 11), offsets)
    }

    @Test
    fun `the map has an entry one past the end, for an edit made at the end`() {
        val (decoded, offsets) = decode("a\\tb")
        assertEquals(decoded.length + 1, offsets.size)
        assertEquals(4, offsets.last())
    }

    @Test
    fun `a line continuation leaves no character and so no entry`() {
        val (decoded, offsets) = decode("a\\\nb")
        assertEquals("ab", decoded)
        assertEquals(listOf(0, 3, 4), offsets)
    }

    // endregion
}
