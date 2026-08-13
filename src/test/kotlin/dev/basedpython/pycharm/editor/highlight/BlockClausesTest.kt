package dev.basedpython.pycharm.editor.highlight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which clause keywords [BlockClauses] pairs, expressed as marked-up source: `|` is the caret and
 * every keyword the scanner pairs with it comes back wrapped in `[]`.
 *
 * The cases that matter are the negative ones. A keyword-set scan (what this replaced) happily
 * joins two adjacent `if`s, or an `if`/`else` to the `try` that follows it, because every word
 * involved is a clause keyword at the same indent; only a grammar rules those out.
 */
class BlockClausesTest {

    /** Runs the scanner with the caret at `|`, bracketing each clause keyword of the chain. */
    private fun chain(marked: String): String? {
        val caret = marked.indexOf('|')
        require(caret >= 0) { "no caret in source" }
        val text = marked.removeRange(caret, caret + 1)
        val chain = BlockClauses.chainAt(text, caret) ?: return null
        return chain.clauses.map { it.range }.sortedByDescending { it.startOffset }
            .fold(text) { acc, r ->
                acc.substring(0, r.startOffset) +
                    "[" + acc.substring(r.startOffset, r.endOffset) + "]" +
                    acc.substring(r.endOffset)
            }
    }

    /** The text the chain spans — what "move to the matching brace" jumps between the ends of. */
    private fun block(marked: String): String? {
        val caret = marked.indexOf('|')
        val text = marked.removeRange(caret, caret + 1)
        return BlockClauses.chainAt(text, caret)?.blockRange?.substring(text)
    }

    // ------------------------------------------------------------------ pairs

    @Test
    fun `elif pairs with its if and else`() {
        assertEquals(
            """
            [if] a:
                pass
            [elif] b:
                pass
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                if a:
                    pass
                el|if b:
                    pass
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `try pairs with except, else and finally`() {
        assertEquals(
            """
            [try]:
                pass
            [except] ValueError:
                pass
            [else]:
                pass
            [finally]:
                pass
            """.trimIndent(),
            chain(
                """
                t|ry:
                    pass
                except ValueError:
                    pass
                else:
                    pass
                finally:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a loop else pairs with the loop, not with an if`() {
        assertEquals(
            """
            [for] x in xs:
                pass
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                for x in xs:
                    pass
                el|se:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `an async loop pairs on its for keyword`() {
        assertEquals(
            """
            async [for] x in xs:
                pass
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                async f|or x in xs:
                    pass
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `match pairs with its case clauses`() {
        assertEquals(
            """
            [match] cmd:
                [case] 1:
                    pass
                [case] _:
                    pass
            """.trimIndent(),
            chain(
                """
                mat|ch cmd:
                    case 1:
                        pass
                    case _:
                        pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a case pairs with its match and sibling cases`() {
        assertEquals(
            """
            [match] cmd:
                [case] 1:
                    if a:
                        pass
                [case] _:
                    pass
            """.trimIndent(),
            chain(
                """
                match cmd:
                    case 1:
                        if a:
                            pass
                    ca|se _:
                        pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a nested chain does not leak into the outer one`() {
        assertEquals(
            """
            [if] a:
                if b:
                    pass
                else:
                    pass
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                if a:
                    if b:
                        pass
                    else:
                        pass
                el|se:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `comments and blank lines between clauses do not break the chain`() {
        assertEquals(
            """
            [if] a:
                pass

            # why not
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                i|f a:
                    pass

                # why not
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a backslash-continued header still pairs`() {
        assertEquals(
            """
            [if] a and \
                    b:
                pass
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                i|f a and \
                        b:
                    pass
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a clause keyword inside a string is not a clause`() {
        assertEquals(
            """
            [if] a:
                doc = ""${'"'}
            else:
            ""${'"'}
            [else]:
                pass
            """.trimIndent(),
            chain(
                """
                i|f a:
                    doc = ""${'"'}
                else:
                ""${'"'}
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    // -------------------------------------------------------------- non-pairs

    @Test
    fun `two adjacent ifs are two statements`() {
        assertNull(
            chain(
                """
                i|f a:
                    pass
                if b:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a following try does not join the if it follows`() {
        assertEquals(
            """
            [if] a:
                pass
            [else]:
                pass
            try:
                pass
            finally:
                pass
            """.trimIndent(),
            chain(
                """
                if a:
                    pass
                el|se:
                    pass
                try:
                    pass
                finally:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `an except cannot follow a finally`() {
        assertEquals(
            """
            [try]:
                pass
            [finally]:
                pass
            except ValueError:
                pass
            """.trimIndent(),
            chain(
                """
                t|ry:
                    pass
                finally:
                    pass
                except ValueError:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a conditional expression is not a chain`() {
        assertNull(
            chain(
                """
                x = (
                    1
                    i|f a
                    else 2
                )
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a variable named match is not a match statement`() {
        assertNull(
            chain(
                """
                mat|ch = 1
                case = 2
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a lone if has nothing to pair with`() {
        assertNull(
            chain(
                """
                i|f a:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `the caret must be on the keyword`() {
        assertNull(
            chain(
                """
                if a|bc:
                    pass
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    // ------------------------------------------------------------ block range

    @Test
    fun `the block spans from the head keyword to the end of the last body`() {
        assertEquals(
            """
            if a:
                pass
            else:
                other()
            """.trimIndent(),
            block(
                """
                i|f a:
                    pass
                else:
                    other()

                after()
                """.trimIndent()
            )
        )
    }

    @Test
    fun `the block of a match reaches the end of its last case`() {
        assertEquals(
            """
            match cmd:
                case 1:
                    pass
                case _:
                    pass
            """.trimIndent(),
            block(
                """
                mat|ch cmd:
                    case 1:
                        pass
                    case _:
                        pass
                after()
                """.trimIndent()
            )
        )
    }
}
