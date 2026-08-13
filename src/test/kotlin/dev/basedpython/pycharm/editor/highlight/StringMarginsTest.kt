package dev.basedpython.pycharm.editor.highlight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Where [StringMargins] puts the line, expressed by drawing it: every margin found comes back as a
 * `|` on each line it spans, at the column the trim cuts at. What the assertions show is therefore
 * the picture the editor draws, not a list of offsets. (`>` is the raw string's own margin, and is
 * not part of the source under test.)
 *
 * The cases that matter are the ones where the margin is *not* the indentation of the line you are
 * looking at. It is the smallest indentation in the literal, so an outdented line pulls it left
 * from anywhere; blank lines cannot pull it left at all; and the closing quotes pull it left even
 * though their line holds nothing else — which is the whole reason Java's rule counts that line,
 * and the reason the margin is worth drawing rather than left for the reader to work out.
 */
class StringMarginsTest {

    /** Runs the scanner over [source] and draws every margin it reports into the text. */
    private fun drawn(source: String): String {
        val lines = source.split("\n").toMutableList()
        val lineStarts = mutableListOf(0)
        for (line in lines) lineStarts.add(lineStarts.last() + line.length + 1)

        for (margin in StringMargins.marginsIn(source)) {
            val first = lineStarts.indexOfLast { it <= margin.firstLineStart }
            val last = lineStarts.indexOfLast { it <= margin.lastLineStart }
            for (i in first..last) {
                val line = lines[i].padEnd(margin.indent)
                lines[i] = line.substring(0, margin.indent) + "|" + line.substring(margin.indent)
            }
        }
        return lines.joinToString("\n")
    }

    /** The single margin in [source], or null when there is none. */
    private fun margin(source: String): StringMargin? =
        StringMargins.marginsIn(source).singleOrNull()

    private val q = "\"\"\""

    // ------------------------------------------------------------------ the Java shape

    @Test
    fun `margin is the least indented line`() {
        assertEquals(
            """
            >def f():
            >    text = $q
            >        |hello
            >        |  world
            >        $q
            """.trimMargin(">"),
            drawn(
                """
                >def f():
                >    text = $q
                >        hello
                >          world
                >        $q
                """.trimMargin(">"),
            ),
        )
    }

    @Test
    fun `closing quotes pull the margin left`() {
        // The text is indented eight, the quotes four: four comes off every line, and nothing but
        // the last line says so. This is the margin's reason for existing.
        assertEquals(
            """
            >text = $q
            >    |    hello
            >    $q
            """.trimMargin(">"),
            drawn(
                """
                >text = $q
                >        hello
                >    $q
                """.trimMargin(">"),
            ),
        )
    }

    @Test
    fun `a blank line does not pull the margin left`() {
        assertEquals(
            """
            >text = $q
            >    |hello
            >    |
            >    |world
            >    $q
            """.trimMargin(">"),
            drawn(
                """
                >text = $q
                >    hello
                >
                >    world
                >    $q
                """.trimMargin(">"),
            ),
        )
    }

    @Test
    fun `a whitespace-only line does not pull the margin left either`() {
        assertEquals(4, margin("text = $q\n    hello\n  \n    world\n    $q\n")?.indent)
    }

    // ------------------------------------------------------------------ the Python shapes

    @Test
    fun `text on the opening line is outside the margin`() {
        // The docstring shape, which Java cannot write. `Summary.` starts after the quotes, so it
        // has no indentation to lose and no margin to be measured against; the lines below it do.
        assertEquals(
            """
            >def f():
            >    ${q}Summary.
            >        |
            >        |More, indented under the def.
            >        $q
            """.trimMargin(">"),
            drawn(
                """
                >def f():
                >    ${q}Summary.
                >
                >        More, indented under the def.
                >        $q
                """.trimMargin(">"),
            ),
        )
    }

    @Test
    fun `closing quotes on the last line of text still bound the margin`() {
        assertEquals(
            """
            >text = $q
            >    |hello
            >    |world$q
            """.trimMargin(">"),
            drawn(
                """
                >text = $q
                >    hello
                >    world$q
                """.trimMargin(">"),
            ),
        )
    }

    @Test
    fun `single-quoted triple strings work the same`() {
        assertEquals(4, margin("text = '''\n    hello\n    '''\n")?.indent)
    }

    @Test
    fun `a prefixed literal is still a literal`() {
        for (prefix in listOf("f", "r", "rb", "F", "B")) {
            assertEquals(
                4,
                margin("text = $prefix$q\n    hello\n    $q\n")?.indent,
                "$prefix-string",
            )
        }
    }

    // ------------------------------------------------------------------ nothing to draw

    @Test
    fun `no margin when the lines share no indentation`() {
        assertNull(margin("text = $q\nhello\nworld\n$q\n"))
    }

    @Test
    fun `no margin on a single-line literal`() {
        assertNull(margin("text = $q    hello    $q\n"))
    }

    @Test
    fun `no margin on a single-quoted literal`() {
        assertNull(margin("text = \"    hello\"\n"))
    }

    @Test
    fun `no margin on an empty literal`() {
        assertNull(margin("text = $q$q\n"))
    }

    @Test
    fun `quotes inside a comment are not a literal`() {
        assertEquals(emptyList<StringMargin>(), StringMargins.marginsIn("# $q\n#     hello\n"))
    }

    // ------------------------------------------------------------------ while typing

    @Test
    fun `an unterminated literal is measured from the lines it has`() {
        // Half-typed, and the margin already reads off the text. The last line is not treated as a
        // closing line — it is the one being written — so pressing Enter does not flatten the
        // margin to nothing and take the line away between keystrokes.
        assertEquals(4, margin("text = $q\n    hello\n")?.indent)
        assertEquals(4, margin("text = $q\n    hello\n  ")?.indent)
    }

    // ------------------------------------------------------------------ where it is drawn

    @Test
    fun `the margin is anchored on the line that defines it`() {
        val source = "x = $q\n        a\n    $q\n"
        val margin = checkNotNull(margin(source))
        assertEquals(4, margin.indent)
        // The closing line, four characters in — whitespace the whole way, so asking the editor
        // for that offset's x gives the column the trim really cuts at, tabs and all.
        assertEquals(source.indexOf("    $q") + 4, margin.anchorOffset)
        // Drawn beside the text, though, and not down past it: the anchor's line is where the
        // rule points, not where it runs.
        assertEquals(source.indexOf("        a"), margin.firstLineStart)
        assertEquals(margin.firstLineStart, margin.lastLineStart)
    }

    @Test
    fun `the rule stops where the closing quotes start`() {
        val source = "x = $q\n    a\n    b\n    $q\n"
        val margin = checkNotNull(margin(source))
        assertEquals(source.indexOf("    a"), margin.firstLineStart)
        assertEquals(source.indexOf("    b"), margin.lastLineStart)
    }

    @Test
    fun `no margin when the quotes are all the literal has`() {
        // `"""\n    """` holds one blank line. There is a margin and no text to draw it beside.
        assertNull(margin("x = $q\n    $q\n"))
    }

    @Test
    fun `the anchor prefers the closing line when it ties`() {
        val source = "x = $q\n    a\n    $q\n"
        assertEquals(source.indexOf("    $q") + 4, margin(source)?.anchorOffset)
    }

    @Test
    fun `the anchor falls back to the first line reaching the margin`() {
        // Closing quotes indented past the text: the text's own line is what defines the margin.
        val source = "x = $q\n    a\n        $q\n"
        val margin = checkNotNull(margin(source))
        assertEquals(4, margin.indent)
        assertEquals(source.indexOf("    a") + 4, margin.anchorOffset)
    }

    @Test
    fun `every literal in a file gets its own margin`() {
        val source = "a = $q\n    one\n    $q\nb = $q\n      two\n  $q\n"
        assertEquals(listOf(4, 2), StringMargins.marginsIn(source).map { it.indent })
    }
}
