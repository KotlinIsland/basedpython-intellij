package dev.basedpython.pycharm.run.main

import dev.basedpython.pycharm.run.model.ByReplies
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The command line the argument form writes, and the way back from one.
 *
 * Both directions matter: the form has to be able to re-open on what it wrote, on what a user typed
 * by hand, and on what the previous run remembered — and to say plainly when it cannot, rather than
 * quietly dropping the part it did not understand.
 */
class ByMainArgumentsTest {

    /** `def main([signature])` as `by` reads it; see [ByReplies]. */
    private fun main(signature: String): ByMainFunction = ByReplies.main(signature)

    @Test
    fun `values are written by name`() {
        val main = main("name: str, count: int = 1")
        assertEquals(
            listOf("--name=bob", "--count=3"),
            ByMainArguments.arguments(main, mapOf("name" to "bob", "count" to "3")),
        )
    }

    @Test
    fun `an omitted parameter says nothing at all`() {
        val main = main("name: str, count: int = 1")
        assertEquals(listOf("--name=bob"), ByMainArguments.arguments(main, mapOf("name" to "bob")))
    }

    @Test
    fun `a bool is a flag, either way round`() {
        val main = main("verbose: bool = False")
        assertEquals(listOf("--verbose"), ByMainArguments.arguments(main, mapOf("verbose" to "true")))
        assertEquals(listOf("--no-verbose"), ByMainArguments.arguments(main, mapOf("verbose" to "false")))
    }

    @Test
    fun `an underscore is written as a dash`() {
        val main = main("out_dir: Path")
        assertEquals(listOf("--out-dir=/tmp/x y"), ByMainArguments.arguments(main, mapOf("out_dir" to "/tmp/x y")))
        assertEquals("\"--out-dir=/tmp/x y\"", ByMainArguments.format(main, mapOf("out_dir" to "/tmp/x y")))
    }

    @Test
    fun `a value that looks like an option is joined to its flag`() {
        // argparse reads `--name -x` as two options and fails on `expected one argument`, and the
        // same happens to `--ratio -1e3`, which is not what its negative-number pattern matches.
        val main = main("name: str, ratio: float = 1.0")
        val values = mapOf("name" to "-x", "ratio" to "-1e3")
        assertEquals(listOf("--name=-x", "--ratio=-1e3"), ByMainArguments.arguments(main, values))
        assertEquals(values, ByMainArguments.parse(main, ByMainArguments.format(main, values)))
    }

    @Test
    fun `a value holding an equals sign keeps it`() {
        // argparse and the reader both split at the first `=`.
        val main = main("expr: str")
        val values = mapOf("expr" to "a=b")
        assertEquals(values, ByMainArguments.parse(main, ByMainArguments.format(main, values)))
    }

    /**
     * Each case with what CPython's `int()` and `float()` said about it — measured on 3.9 and 3.14,
     * which agree on every one.
     */
    @Test
    fun `numbers are validated by Python's grammar, not Kotlin's`() {
        val measured = listOf(
            Triple("1", true, true), Triple("-1", true, true), Triple("+1", true, true),
            Triple(" 7 ", true, true), Triple("\t8\n", true, true), Triple("007", true, true),
            Triple("1_000", true, true), Triple("1__000", false, false), Triple("_1", false, false),
            Triple("1_", false, false), Triple("9223372036854775808", true, true),
            Triple("-99999999999999999999999", true, true), Triple("0x10", false, false),
            Triple("1.0", false, true), Triple("1e3", false, true), Triple("٣", true, true),
            Triple("", false, false), Triple("-", false, false), Triple("1 2", false, false),
            Triple("1f", false, false), Triple("0x1p3", false, false), Triple("1.5", false, true),
            Triple(".5", false, true), Triple("5.", false, true), Triple("1_0.5", false, true),
            Triple("1e1_0", false, true), Triple("1e_10", false, false), Triple("inf", false, true),
            Triple("-Infinity", false, true), Triple("NaN", false, true), Triple("+nan", false, true),
            Triple("infinit", false, false), Triple("1.e3", false, true), Triple(".e3", false, false),
            Triple("1e", false, false), Triple("1.5_", false, false), Triple("1.5e+3", false, true),
            Triple("  -2.5E-3  ", false, true), Triple("0b1", false, false), Triple("1j", false, false),
            Triple("١٢.٥", false, true), Triple("1 ", true, true),
            Triple(" 1", true, true), Triple("- 1", false, false), Triple("+-1", false, false),
            Triple("1._5", false, false), Triple("1_.5", false, false), Triple("iNfInItY", false, true),
            Triple("1.5d", false, false), Triple("0", true, true), Triple("-0", true, true),
            Triple("00_0", true, true), Triple("²", false, false), Triple("1​", false, false),
        )
        for ((text, int, float) in measured) {
            val shown = text.map { if (it.code in 0x21..0x7e) "$it" else "\\u%04x".format(it.code) }.joinToString("")
            assertEquals(int, PythonNumbers.isInt(text), "int('$shown')")
            assertEquals(float, PythonNumbers.isFloat(text), "float('$shown')")
        }
    }

    @Test
    fun `what the form writes, the form reads back`() {
        val main = main("name: str, count: int = 1, out_dir: Path = Path('.'), verbose: bool = False")
        val values = mapOf("name" to "bob", "count" to "3", "out_dir" to "/tmp", "verbose" to "true")
        assertEquals(values, ByMainArguments.parse(main, ByMainArguments.format(main, values)))
    }

    @Test
    fun `a hand-written command line is read in every spelling it accepts`() {
        val main = main("name: str, count: int = 1, out_dir: Path = Path('.'), verbose: bool = False")
        assertEquals(
            mapOf("name" to "bob", "count" to "3", "out_dir" to "/tmp", "verbose" to "false"),
            ByMainArguments.parse(main, "bob 3 --out_dir=/tmp --no-verbose"),
        )
    }

    @Test
    fun `a bool takes no positional slot`() {
        // `--verbose` is a flag, so `3` still lines up with `count`, not with it.
        val main = main("verbose: bool = False, count: int = 1")
        assertEquals(mapOf("count" to "3"), ByMainArguments.parse(main, "3"))
    }

    @Test
    fun `a keyword-only parameter takes no positional slot either`() {
        val main = main("a: int, *, b: int = 2")
        assertEquals(mapOf("a" to "1"), ByMainArguments.parse(main, "1"))
        assertNull(ByMainArguments.parse(main, "1 2"))
    }

    @Test
    fun `a negative number is a value, not a flag`() {
        assertEquals(mapOf("count" to "-3"), ByMainArguments.parse(main("count: int"), "-3"))
    }

    @Test
    fun `a command line the form cannot express asks to stay text`() {
        val main = main("name: str")
        assertNull(ByMainArguments.parse(main, "--nope 1"))
        assertNull(ByMainArguments.parse(main, "--name"))
        assertNull(ByMainArguments.parse(main, "bob --name bob"))
        assertNull(ByMainArguments.parse(main, "-h"))
        assertNull(ByMainArguments.parse(main, "bob extra"))
    }

    @Test
    fun `missing names what a run would die on`() {
        val main = main("name: str, count: int = 1")
        assertEquals(listOf("name"), ByMainArguments.missing(main, "--count 3").map { it.name })
        assertEquals(emptyList<String>(), ByMainArguments.missing(main, "bob").map { it.name })
    }

    @Test
    fun `a run is only interrupted for arguments it cannot start without`() {
        val required = main("a: int")
        assertTrue(ByMainArguments.needed(required, ""), "this run would die on `required: a`")
        assertFalse(ByMainArguments.needed(required, "--a 1"), "already answered")
        assertFalse(ByMainArguments.needed(required, "1"), "answered positionally")
        assertFalse(ByMainArguments.needed(main("a: int = 1"), ""), "optional, so it just runs")
        assertFalse(ByMainArguments.needed(main("db: Db"), ""), "no entry point to give arguments to")
        assertFalse(ByMainArguments.needed(null, ""), "no readable main; the module speaks for itself")
    }

    @Test
    fun `a command line the form cannot read is taken at its word`() {
        // It was written by hand; guessing that it is incomplete is worse than letting it run.
        assertEquals(emptyList<ByMainParameter>(), ByMainArguments.missing(main("name: str"), "--nope 1"))
    }
}
