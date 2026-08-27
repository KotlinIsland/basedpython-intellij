package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The payload here was captured from `by server` (ruff/0.16.2+452) for [SOURCE] — the semantic
 * tokens verbatim, the symbol ranges verbatim — so this asserts what the plugin makes of what the
 * server actually sends, not of a payload invented to suit it.
 *
 * The file is deliberately a list of the shapes the old hand-written detector got wrong: an `async
 * def`, a docstring under `let`, a docstring under an annotated field, a nested method. All of them
 * are here because `by` marked them, and none of them is named anywhere in the plugin.
 */
class ByDocstringTokensTest {

    private companion object {
        const val Q = "\"\"\""

        /** Lines are numbered in the comments because the captured payload is line-relative. */
        val SOURCE = listOf(
            "${Q}Module docs.",      // 0
            "",                      // 1
            "More.",                 // 2
            Q,                       // 3
            "",                      // 4
            "",                      // 5
            "let a = 1",             // 6
            "${Q}Attribute docs.$Q", // 7
            "",                      // 8
            "",                      // 9
            "async def fetch(url: str) -> str:", // 10
            "    ${Q}Fetch docs.$Q", // 11
            "    return url",        // 12
            "",                      // 13
            "",                      // 14
            "class Outer:",          // 15
            "    ${Q}Outer docs.$Q", // 16
            "",                      // 17
            "    field: int = 0",    // 18
            "    ${Q}Field docs.$Q", // 19
            "",                      // 20
            "    def method(self) -> None:",   // 21
            "        ${Q}Method docs.$Q",      // 22
        ).joinToString("\n") + "\n"

        const val STRING_TYPE = 10
        const val DOCUMENTATION_BIT = 3

        val DATA = listOf(
            0, 0, 16, 10, 8, 1, 0, 1, 10, 8, 1, 0, 6, 10, 8, 1, 0, 3, 10, 8, 3, 0, 3, 9, 0, 0, 4, 1,
            5, 1, 0, 4, 1, 11, 0, 1, 0, 21, 10, 8, 3, 10, 5, 7, 5, 0, 6, 3, 2, 1, 0, 5, 3, 1, 0, 0,
            8, 3, 1, 0, 1, 4, 17, 10, 8, 1, 11, 3, 2, 0, 3, 6, 5, 1, 1, 1, 4, 17, 10, 8, 2, 4, 5, 5,
            1, 0, 7, 3, 1, 0, 0, 6, 1, 11, 0, 1, 4, 17, 10, 8, 2, 8, 6, 8, 1, 0, 7, 4, 3, 1, 0, 9,
            4, 13, 0, 1, 8, 18, 10, 8,
        )

        /** `documentSymbol`, flattened: name, range start/end, then the `selectionRange` start. */
        val SYMBOLS = listOf(
            symbol(6, 0, 6, 9, 6, 4),     // a
            symbol(10, 0, 12, 14, 10, 10), // fetch
            symbol(15, 0, 22, 26, 15, 6),  // Outer
            symbol(18, 4, 18, 18, 18, 4),  // field
            symbol(21, 4, 22, 26, 21, 8),  // method
        )

        fun symbol(
            startLine: Int, startChar: Int,
            endLine: Int, endChar: Int,
            nameLine: Int, nameChar: Int,
        ): BySymbol {
            val starts = ByDocstringTokens.lineStarts(SOURCE)
            return BySymbol(
                TextRange(starts[startLine] + startChar, starts[endLine] + endChar),
                starts[nameLine] + nameChar,
            )
        }

        fun spans(): List<ByDocstring> =
            ByDocstringTokens.spans(SOURCE, DATA, STRING_TYPE, DOCUMENTATION_BIT, SYMBOLS)

        fun literal(of: ByDocstring): String = of.range.substring(SOURCE)

        fun nameAt(of: ByDocstring): String? =
            of.ownerNameOffset?.let { SOURCE.substring(it).takeWhile { c -> c.isLetterOrDigit() || c == '_' } }
    }

    @Test
    fun `every docstring the server marked is found, and nothing else`() {
        assertEquals(
            listOf(
                "${Q}Module docs.\n\nMore.\n$Q",
                "${Q}Attribute docs.$Q",
                "${Q}Fetch docs.$Q",
                "${Q}Outer docs.$Q",
                "${Q}Field docs.$Q",
                "${Q}Method docs.$Q",
            ),
            spans().map(::literal),
        )
    }

    /** A multi-line docstring arrives as one token per line; it has to come back as one range. */
    @Test
    fun `the pieces of a multi-line docstring are joined`() {
        val module = spans().first()
        assertEquals(SOURCE.indexOf(Q), module.range.startOffset)
        assertEquals("${Q}Module docs.\n\nMore.\n$Q", literal(module))
    }

    @Test
    fun `each docstring is attributed to the symbol hover can answer for`() {
        assertEquals(
            listOf(null, "a", "fetch", "Outer", "field", "method"),
            spans().map(::nameAt),
        )
    }

    /** A module docstring documents the file, and there is no name in the file to ask about. */
    @Test
    fun `the module docstring has no owner`() {
        assertNull(spans().first().ownerNameOffset)
    }

    /**
     * A docstring under `let a = 1` or `field: int = 0` follows its definition instead of sitting
     * inside it, and the definition is itself inside a class whose range also contains the
     * docstring — so the symbol directly above has to win over the one wrapped around it.
     */
    @Test
    fun `a docstring below a definition belongs to it, not to the class around it`() {
        val field = spans().single { literal(it) == "${Q}Field docs.$Q" }
        assertEquals("field", nameAt(field))
    }

    // -------------------------------------------------------------------------
    // decoding
    // -------------------------------------------------------------------------

    @Test
    fun `tokens without the documentation modifier are not docstrings`() {
        val plain = ByDocstringTokens.spans(SOURCE, DATA, STRING_TYPE, documentationBit = 1, symbols = SYMBOLS)
        assertEquals(emptyList<ByDocstring>(), plain)
    }

    /** The first line's token runs to the newline, which is the server's own count, kept as sent. */
    @Test
    fun `a truncated payload is read as far as it goes`() {
        val short = ByDocstringTokens.spans(SOURCE, DATA.take(7), STRING_TYPE, DOCUMENTATION_BIT, SYMBOLS)
        assertEquals(listOf("${Q}Module docs.\n"), short.map(::literal))
    }

    @Test
    fun `an empty payload has no docstrings`() {
        assertEquals(
            emptyList<ByDocstring>(),
            ByDocstringTokens.spans(SOURCE, emptyList(), STRING_TYPE, DOCUMENTATION_BIT, SYMBOLS),
        )
    }
}
