package dev.basedpython.pycharm.run.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The command line the argument form writes, and the way back from one.
 *
 * Both directions matter: the form has to be able to re-open on what it wrote, on what a user typed
 * by hand, and on what the previous run remembered — and to say plainly when it cannot, rather than
 * quietly dropping the part it did not understand.
 */
class ByMainArgumentsTest {

    private fun main(signature: String): ByMainFunction {
        val lines = listOf("def main($signature): ...")
        return ByMainSignature.find({ lines[it] }, lines.size)!!
    }

    @Test
    fun `values are written by name`() {
        val main = main("name: str, count: int = 1")
        assertEquals(
            listOf("--name", "bob", "--count", "3"),
            ByMainArguments.arguments(main, mapOf("name" to "bob", "count" to "3")),
        )
    }

    @Test
    fun `an omitted parameter says nothing at all`() {
        val main = main("name: str, count: int = 1")
        assertEquals(listOf("--name", "bob"), ByMainArguments.arguments(main, mapOf("name" to "bob")))
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
        assertEquals(listOf("--out-dir", "/tmp/x y"), ByMainArguments.arguments(main, mapOf("out_dir" to "/tmp/x y")))
        assertEquals("--out-dir \"/tmp/x y\"", ByMainArguments.format(main, mapOf("out_dir" to "/tmp/x y")))
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
    fun `a command line the form cannot read is taken at its word`() {
        // It was written by hand; guessing that it is incomplete is worse than letting it run.
        assertEquals(emptyList<ByMainParameter>(), ByMainArguments.missing(main("name: str"), "--nope 1"))
    }
}
