package dev.basedpython.pycharm.transpile.explain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive pure-logic unit tests for [TranspilationExplainer]. No IDE fixture required.
 */
class TranspilationExplainerTest {

    private fun explain(src: String) = TranspilationExplainer.explain(src)

    private fun names(src: String) = explain(src).map { it.constructName }

    private fun firstOf(src: String, name: String): TranspilationNote =
        explain(src).first { it.constructName == name }

    // ------------------------------------------------------------------
    // Empty / trivial input
    // ------------------------------------------------------------------

    @Test
    fun `null input yields no notes`() {
        assertEquals(emptyList<TranspilationNote>(), TranspilationExplainer.explain(null))
    }

    @Test
    fun `empty input yields no notes`() {
        assertEquals(emptyList<TranspilationNote>(), explain(""))
    }

    @Test
    fun `blank lines yield no notes`() {
        assertEquals(emptyList<TranspilationNote>(), explain("\n\n   \n\t\n"))
    }

    @Test
    fun `plain python only yields no notes`() {
        val src = """
            def add(a, b):
                return a + b

            x = add(1, 2)
            print(x)
        """.trimIndent()
        assertEquals(emptyList<TranspilationNote>(), explain(src))
    }

    @Test
    fun `comment-only line yields no notes`() {
        assertEquals(emptyList<TranspilationNote>(), explain("# this ?. is in a comment"))
    }

    // ------------------------------------------------------------------
    // null-safe access  a?.b
    // ------------------------------------------------------------------

    @Test
    fun `null safe access recognized`() {
        val notes = explain("y = user?.name")
        assertEquals(1, notes.size)
        assertEquals("null-safe access", notes[0].constructName)
        assertEquals(1, notes[0].lineNumber)
    }

    @Test
    fun `null safe access snippet trimmed`() {
        val note = firstOf("    y = user?.name   ", "null-safe access")
        assertEquals("y = user?.name", note.bySnippet)
    }

    @Test
    fun `plain dot access is not null safe`() {
        assertFalse(names("y = user.name").contains("null-safe access"))
    }

    // ------------------------------------------------------------------
    // null-safe index  a?[i]
    // ------------------------------------------------------------------

    @Test
    fun `null safe index recognized`() {
        val notes = explain("v = items?[0]")
        assertTrue(notes.any { it.constructName == "null-safe index" })
    }

    @Test
    fun `plain index is not null safe index`() {
        assertFalse(names("v = items[0]").contains("null-safe index"))
    }

    // ------------------------------------------------------------------
    // elvis  a ?: b
    // ------------------------------------------------------------------

    @Test
    fun `elvis recognized`() {
        val notes = explain("name = nickname ?: \"anon\"")
        assertTrue(notes.any { it.constructName == "elvis operator" })
    }

    @Test
    fun `elvis line number correct on later line`() {
        val src = "x = 1\ny = 2\nz = a ?: b"
        assertEquals(3, firstOf(src, "elvis operator").lineNumber)
    }

    // ------------------------------------------------------------------
    // null-coalescing  a ?? b
    // ------------------------------------------------------------------

    @Test
    fun `null coalescing recognized`() {
        val notes = explain("v = a ?? b")
        assertTrue(notes.any { it.constructName == "null-coalescing operator" })
    }

    @Test
    fun `null coalescing does not also report elvis`() {
        assertFalse(names("v = a ?? b").contains("elvis operator"))
    }

    // ------------------------------------------------------------------
    // non-null assertion  expr!!
    // ------------------------------------------------------------------

    @Test
    fun `non null assertion recognized`() {
        val notes = explain("v = maybe!!")
        assertTrue(notes.any { it.constructName == "non-null assertion" })
    }

    @Test
    fun `non null assertion after paren`() {
        assertTrue(names("v = compute()!!").contains("non-null assertion"))
    }

    @Test
    fun `not equals is not a non null assertion`() {
        assertFalse(names("if a != b:").contains("non-null assertion"))
    }

    // ------------------------------------------------------------------
    // data-class modifier
    // ------------------------------------------------------------------

    @Test
    fun `data class keyword recognized`() {
        val notes = explain("data class Point:")
        assertTrue(notes.any { it.constructName == "data-class modifier" })
    }

    @Test
    fun `data decorator recognized`() {
        assertTrue(names("@data").contains("data-class modifier"))
    }

    @Test
    fun `dataclass decorator recognized`() {
        assertTrue(names("@dataclass").contains("data-class modifier"))
    }

    @Test
    fun `plain class is not data class`() {
        assertFalse(names("class Point:").contains("data-class modifier"))
    }

    // ------------------------------------------------------------------
    // pattern match / case
    // ------------------------------------------------------------------

    @Test
    fun `match header recognized`() {
        assertTrue(names("match command:").contains("pattern match"))
    }

    @Test
    fun `case header recognized`() {
        assertTrue(names("    case 1:").contains("match case"))
    }

    @Test
    fun `match without colon not recognized`() {
        assertFalse(names("x = match_score").contains("pattern match"))
    }

    @Test
    fun `match and cases full block`() {
        val src = """
            match cmd:
                case "go":
                    move()
                case _:
                    stop()
        """.trimIndent()
        val ns = names(src)
        assertEquals(1, ns.count { it == "pattern match" })
        assertEquals(2, ns.count { it == "match case" })
    }

    // ------------------------------------------------------------------
    // pipe operator
    // ------------------------------------------------------------------

    @Test
    fun `pipe operator recognized`() {
        assertTrue(names("result = data |> transform").contains("pipe operator"))
    }

    @Test
    fun `bitwise or is not a pipe`() {
        assertFalse(names("flags = a | b").contains("pipe operator"))
    }

    // ------------------------------------------------------------------
    // string interpolation
    // ------------------------------------------------------------------

    @Test
    fun `string interpolation recognized`() {
        assertTrue(names("msg = \"hello \${name}\"").contains("string interpolation"))
    }

    @Test
    fun `plain string not interpolation`() {
        assertFalse(names("msg = \"hello name\"").contains("string interpolation"))
    }

    // ------------------------------------------------------------------
    // type modifiers
    // ------------------------------------------------------------------

    @Test
    fun `val modifier recognized`() {
        assertTrue(names("val x = 1").contains("type modifier"))
    }

    @Test
    fun `var modifier recognized`() {
        assertTrue(names("var y = 2").contains("type modifier"))
    }

    @Test
    fun `let modifier recognized`() {
        assertTrue(names("let z = 3").contains("type modifier"))
    }

    @Test
    fun `const modifier recognized`() {
        assertTrue(names("const PI = 3").contains("type modifier"))
    }

    @Test
    fun `value substring is not a modifier`() {
        // `value` starts with `val` but must not match because the keyword needs whitespace + ident.
        assertFalse(names("value = 1").contains("type modifier"))
    }

    // ------------------------------------------------------------------
    // multiple constructs / ordering
    // ------------------------------------------------------------------

    @Test
    fun `multiple constructs across lines emit in source order`() {
        val src = """
            data class User:
                val name = "x"
            u = user?.name ?: "anon"
        """.trimIndent()
        val notes = explain(src)
        // line 1: data-class; line 2: type modifier; line 3: null-safe access + elvis
        assertTrue(notes.any { it.constructName == "data-class modifier" && it.lineNumber == 1 })
        assertTrue(notes.any { it.constructName == "type modifier" && it.lineNumber == 2 })
        assertTrue(notes.any { it.constructName == "null-safe access" && it.lineNumber == 3 })
        assertTrue(notes.any { it.constructName == "elvis operator" && it.lineNumber == 3 })
        // notes must be in non-decreasing line order
        val lines = notes.map { it.lineNumber }
        assertEquals(lines.sorted(), lines)
    }

    @Test
    fun `two constructs on same line both reported`() {
        val notes = explain("v = a?.b ?? c")
        val ns = notes.map { it.constructName }
        assertTrue(ns.contains("null-safe access"))
        assertTrue(ns.contains("null-coalescing operator"))
    }

    @Test
    fun `every note carries non-blank explanation`() {
        val src = "v = user?.name ?: maybe!! \n match x:\n  case 1:\n val k = 1 |> f"
        for (note in explain(src)) {
            assertTrue("explanation blank for ${note.constructName}", note.explanation.isNotBlank())
            assertTrue("snippet blank for ${note.constructName}", note.bySnippet.isNotBlank())
        }
    }

    // ------------------------------------------------------------------
    // comments & strings tolerance
    // ------------------------------------------------------------------

    @Test
    fun `construct in trailing comment is ignored`() {
        assertEquals(emptyList<TranspilationNote>(), explain("x = 1  # user?.name ?: y"))
    }

    @Test
    fun `hash inside string is not treated as comment`() {
        // `?.` is real code; `#` is inside the string and must not truncate it away.
        assertTrue(names("y = obj?.tag  # note").isNotEmpty())
        assertTrue(names("y = \"a#b\" + obj?.tag").contains("null-safe access"))
    }

    // ------------------------------------------------------------------
    // robustness / garbage
    // ------------------------------------------------------------------

    @Test
    fun `garbage input does not crash`() {
        val garbage = "?.?.?!!??::|>\${}}{@@@@\n ￿????\n)]}!!"
        // Should simply return some (possibly empty) list without throwing.
        assertNotNull(TranspilationExplainer.explain(garbage))
    }

    @Test
    fun `unterminated string does not crash`() {
        assertNotNull(explain("x = \"unterminated \${"))
    }

    @Test
    fun `crlf line endings counted correctly`() {
        val src = "x = 1\r\ny = a ?: b\r\n"
        assertEquals(2, firstOf(src, "elvis operator").lineNumber)
    }

    @Test
    fun `large repetitive input handled`() {
        val src = (1..500).joinToString("\n") { "v$it = user?.name" }
        val notes = explain(src)
        assertEquals(500, notes.count { it.constructName == "null-safe access" })
        assertEquals(500, notes.last().lineNumber)
    }

    // ------------------------------------------------------------------
    // overloads / metadata
    // ------------------------------------------------------------------

    @Test
    fun `two-arg overload ignores python source for results`() {
        val a = TranspilationExplainer.explain("y = user?.name", "y = user.name if user is not None else None")
        val b = TranspilationExplainer.explain("y = user?.name")
        assertEquals(b, a)
    }

    @Test
    fun `recognizedConstructs lists all emitted names`() {
        val src = """
            data class User:
                val name = "x"
            u = user?.name
            v = items?[0]
            w = a ?: b
            n = a ?? b
            m = maybe!!
            match cmd:
                case 1:
                    pass
            r = data |> f
            s = "hi ${'$'}{name}"
        """.trimIndent()
        val emitted = explain(src).map { it.constructName }.toSet()
        // Every emitted name must be declared in recognizedConstructs.
        for (name in emitted) {
            assertTrue("$name missing from recognizedConstructs", TranspilationExplainer.recognizedConstructs.contains(name))
        }
        // And the headline constructs are all present.
        assertTrue(emitted.containsAll(setOf(
            "data-class modifier", "type modifier", "null-safe access", "null-safe index",
            "elvis operator", "null-coalescing operator", "non-null assertion",
            "pattern match", "match case", "pipe operator", "string interpolation",
        )))
    }
}
