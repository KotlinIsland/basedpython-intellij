package dev.basedpython.pycharm.run.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reading `main`'s command-line interface out of `.by` source.
 *
 * The rules under test are basedpython's own, from the `main_function` transpiler pass: which
 * annotations the command line can fill, which parameter stops `main` being an entry point at all,
 * and which `main` a module ends up with.
 */
class ByMainSignatureTest {

    private fun main(source: String): ByMainFunction? {
        val lines = source.trimIndent().lines()
        return ByMainSignature.find({ lines[it] }, lines.size)
    }

    @Test
    fun `a parameter is the command line`() {
        val main = main(
            """
            def main(a: int):
                print(a)
            """
        )!!
        assertEquals(listOf("a"), main.parameters.map { it.name })
        assertEquals(ByCliType.INT, main.parameters[0].type)
        assertTrue(main.parameters[0].isRequired)
        assertEquals(listOf("a"), main.required.map { it.name })
        assertTrue(main.isEntryPoint)
        assertTrue(main.takesArguments)
    }

    @Test
    fun `a parameter with a default is optional`() {
        val main = main("def main(name: str, count: int = 1): ...")!!
        assertEquals(listOf(null, "1"), main.parameters.map { it.default })
        assertEquals(listOf("name"), main.required.map { it.name })
    }

    @Test
    fun `only five annotations reach the command line`() {
        val main = main(
            """
            def main(a: str, b: int, c: float, d: bool, e: Path, f: pathlib.Path, g: object = None):
                ...
            """
        )!!
        assertEquals(
            listOf(ByCliType.STR, ByCliType.INT, ByCliType.FLOAT, ByCliType.BOOL, ByCliType.PATH, ByCliType.PATH, null),
            main.parameters.map { it.type },
        )
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), main.exposed.map { it.name })
    }

    @Test
    fun `a required parameter the command line cannot supply is no entry point`() {
        // basedpython emits no `__main__` guard at all here, so running the module does nothing.
        val main = main("def main(db: Database): ...")!!
        assertEquals("db", main.blockedBy?.name)
        assertFalse(main.isEntryPoint)
        assertFalse(main.takesArguments)
    }

    @Test
    fun `an unexposed parameter with a default only keeps its default`() {
        val main = main("def main(name: str, db: Database = connect()): ...")!!
        assertNull(main.blockedBy)
        assertEquals(listOf("name"), main.exposed.map { it.name })
    }

    @Test
    fun `variadics are dropped, and open the keyword-only group`() {
        val main = main("def main(a: int, *args, b: str = 'x', **kwargs): ...")!!
        assertEquals(listOf("a", "b"), main.parameters.map { it.name })
        assertEquals(ByParameterKind.ANY, main.parameters[0].kind)
        assertEquals(ByParameterKind.KEYWORD, main.parameters[1].kind)
    }

    @Test
    fun `a slash makes everything behind it positional-only`() {
        val main = main("def main(a: int, /, b: int, *, c: int): ...")!!
        assertEquals(
            listOf(ByParameterKind.POSITIONAL, ByParameterKind.ANY, ByParameterKind.KEYWORD),
            main.parameters.map { it.kind },
        )
    }

    @Test
    fun `a signature spanning lines is read whole`() {
        val main = main(
            """
            def main(
                name: str,
                out_dir: Path = Path("."),
            ) -> None:
                ...
            """
        )!!
        assertEquals(listOf("name", "out_dir"), main.parameters.map { it.name })
        assertEquals("Path(\".\")", main.parameters[1].default)
    }

    @Test
    fun `a comma inside a default is not a separator`() {
        val main = main("""def main(name: str = "a, b", other: dict[str, int] = {}): ...""")!!
        assertEquals(listOf("name", "other"), main.parameters.map { it.name })
        assertEquals("\"a, b\"", main.parameters[0].default)
    }

    @Test
    fun `an underscore in a name answers to both spellings`() {
        val parameter = main("def main(out_dir: Path): ...")!!.parameters[0]
        assertEquals(listOf("--out-dir", "--out_dir"), parameter.flags)
        assertEquals("--no-out-dir", parameter.negativeFlag)
    }

    @Test
    fun `the docstring is the description argparse would print`() {
        val main = main(
            """
            def main(name: str):
                ""${'"'}greet someone""${'"'}
                print(name)
            """
        )!!
        assertEquals("greet someone", main.docstring)
    }

    @Test
    fun `async main is still main`() {
        val main = main("async def main(port: int): ...")!!
        assertTrue(main.isAsync)
        assertEquals(listOf("port"), main.parameters.map { it.name })
    }

    @Test
    fun `a private main is not an entry point`() {
        assertNull(main("private def main(a: int): ..."))
    }

    @Test
    fun `an exported main is`() {
        assertEquals(listOf("a"), main("export def main(a: int): ...")!!.parameters.map { it.name })
    }

    @Test
    fun `a nested main is a method, not an entry point`() {
        assertNull(
            main(
                """
                class Runner:
                    def main(self, a: int): ...
                """
            )
        )
    }

    @Test
    fun `the last main wins, as the binding does`() {
        val main = main(
            """
            def main(a: int): ...
            def main(b: str): ...
            """
        )!!
        assertEquals(listOf("b"), main.parameters.map { it.name })
    }

    @Test
    fun `a module that invokes main keeps its own entry point`() {
        val guarded = """
            def main(a: int): ...
            if __name__ == "__main__":
                main(1)
        """.trimIndent().lines()
        assertTrue(ByMainSignature.invokesMain({ guarded[it] }, guarded.size))

        val called = """
            def main(a: int): ...
            main(1)
        """.trimIndent().lines()
        assertTrue(ByMainSignature.invokesMain({ called[it] }, called.size))

        val plain = listOf("def main(a: int): ...")
        assertFalse(ByMainSignature.invokesMain({ plain[it] }, plain.size))
    }
}
