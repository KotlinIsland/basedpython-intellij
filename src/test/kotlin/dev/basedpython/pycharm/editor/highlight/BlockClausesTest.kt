package dev.basedpython.pycharm.editor.highlight

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Which keywords [BlockClauses] pairs, expressed as marked-up source: `|` is the caret and every
 * keyword the scanner pairs with it comes back wrapped in `[]`. `chain` asks for the clause
 * keywords alone — the structure the code block handler navigates — and `family` for everything
 * that highlights together, which adds the statements leaving a `def` or a loop.
 *
 * The cases that matter are the negative ones. A keyword-set scan (what this replaced) happily
 * joins two adjacent `if`s, or an `if`/`else` to the `try` that follows it, because every word
 * involved is a clause keyword at the same indent; only a grammar rules those out. The same goes
 * for the jumps: `break` and `return` are keyword hits anywhere in a body, and only the nesting
 * rules say which block they leave.
 */
class BlockClausesTest {

    /** Runs the scanner with the caret at `|`, bracketing each clause keyword of the chain. */
    private fun chain(marked: String): String? = marked.markUp { text, caret ->
        BlockClauses.chainAt(text, caret)?.clauses?.map { it.range }
    }

    /** The same, for the whole family: the chain plus the statements that leave the block. */
    private fun family(marked: String): String? = marked.markUp { text, caret ->
        BlockClauses.familyAt(text, caret).takeIf { it.isNotEmpty() }
    }

    /** Strips the caret, runs [ranges], and brackets each range it returns. */
    private fun String.markUp(ranges: (String, Int) -> List<TextRange>?): String? {
        val caret = indexOf('|')
        require(caret >= 0) { "no caret in source" }
        val text = removeRange(caret, caret + 1)
        val found = ranges(text, caret) ?: return null
        return found.sortedByDescending { it.startOffset }
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
    fun `a one-line branch does not end the chain`() {
        assertEquals(
            """
            [if] a:
                pass
            [else]: pass
            """.trimIndent(),
            chain(
                """
                i|f a:
                    pass
                else: pass
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

    // ----------------------------------------------------- exits and jumps

    @Test
    fun `a def pairs with the returns and raises that leave it`() {
        assertEquals(
            """
            [def] f(x):
                if x:
                    [return] 1
                [raise] ValueError
            """.trimIndent(),
            family(
                """
                d|ef f(x):
                    if x:
                        return 1
                    raise ValueError
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a return pairs with its def and the other exits`() {
        assertEquals(
            """
            async [def] f(x):
                if x:
                    [return] 1
                [return] 2
            """.trimIndent(),
            family(
                """
                async def f(x):
                    if x:
                        return 1
                    re|turn 2
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a nested def keeps its own returns`() {
        assertEquals(
            """
            [def] outer():
                def inner():
                    return 1
                [return] inner
            """.trimIndent(),
            family(
                """
                d|ef outer():
                    def inner():
                        return 1
                    return inner
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a loop pairs with its breaks, continues and else`() {
        assertEquals(
            """
            [while] go:
                if a:
                    [continue]
                [break]
            [else]:
                pass
            """.trimIndent(),
            family(
                """
                wh|ile go:
                    if a:
                        continue
                    break
                else:
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a break pairs with the loop it is nested in, not the one outside`() {
        assertEquals(
            """
            for x in xs:
                [for] y in ys:
                    [break]
                continue
            """.trimIndent(),
            family(
                """
                for x in xs:
                    for y in ys:
                        br|eak
                    continue
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a break in a loop's else belongs to the loop outside it`() {
        assertEquals(
            """
            [for] x in xs:
                for y in ys:
                    pass
                else:
                    [break]
            """.trimIndent(),
            family(
                """
                for x in xs:
                    for y in ys:
                        pass
                    else:
                        br|eak
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a jump does not reach a loop outside its function`() {
        assertNull(
            family(
                """
                for x in xs:
                    def f():
                        brea|k
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a def with no exits has nothing to pair with`() {
        assertNull(
            family(
                """
                d|ef f():
                    pass
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a return outside any function pairs with nothing`() {
        assertNull(
            family(
                """
                x = 1
                re|turn x
                """.trimIndent()
            )
        )
    }

    @Test
    fun `a suite written on its head's own line still carries its exits`() {
        assertEquals(
            """
            [def] f(x):
                if x: [return] 1
                [return] 2
            """.trimIndent(),
            family(
                """
                d|ef f(x):
                    if x: return 1
                    return 2
                """.trimIndent()
            )
        )
    }

    @Test
    fun `an inline break belongs to the loop that heads its line`() {
        assertEquals(
            """
            for x in xs:
                [for] y in ys: [break]
            """.trimIndent(),
            family(
                """
                for x in xs:
                    for y in ys: bre|ak
                """.trimIndent()
            )
        )
    }

    @Test
    fun `exits are not clauses of the block they leave`() {
        assertNull(
            chain(
                """
                d|ef f():
                    return 1
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
