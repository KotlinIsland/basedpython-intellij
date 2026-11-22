package dev.basedpython.pycharm.run.django

import dev.basedpython.pycharm.lsp.ext.ByDjangoArgument
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The form and the command line saying the same, on `migrate`'s arguments and one of each other kind. */
class ByManageArgumentsTest {

    private fun argument(name: String, kind: String, vararg flags: String) = ByDjangoArgument(name, flags.toList(), kind)

    private val label = argument("app_label", ByDjangoArgument.VALUE)
    private val migration = argument("migration_name", ByDjangoArgument.VALUE)
    private val database = argument("database", ByDjangoArgument.VALUE, "--database")
    private val fake = argument("fake", ByDjangoArgument.FLAG, "--fake")
    private val verbosity = argument("verbosity", ByDjangoArgument.VALUE, "-v", "--verbosity")
    private val exclude = argument("exclude", ByDjangoArgument.APPEND, "-e", "--exclude")
    private val tags = argument("tags", ByDjangoArgument.LIST, "--tag")
    private val loud = argument("loud", ByDjangoArgument.COUNT, "-l")
    private val arguments = ByManageArguments(listOf(label, migration, database, fake, verbosity, exclude, tags, loud))

    private fun words(line: String) = line.split(' ').filter { it.isNotEmpty() }

    private fun write(line: String, argument: ByDjangoArgument, vararg values: String) =
        arguments.write(words(line), argument, values.toList()).joinToString(" ")

    private fun read(line: String) = arguments.read(words(line)).map { (argument, values) -> argument.name to values }.toMap()

    private fun positionals(line: String, vararg values: Pair<ByDjangoArgument, List<String>>, of: ByManageArguments = arguments) =
        of.writePositionals(words(line), values.toMap()).let { it.words.joinToString(" ") to it.pending.map { pending -> pending.name } }

    @Test
    fun `it reads positionals and options wherever they are`() {
        assertEquals(
            mapOf("app_label" to listOf("polls"), "fake" to listOf(""), "database" to listOf("other"), "migration_name" to listOf("0002")),
            read("polls --fake --database=other 0002"),
        )
        assertEquals(mapOf("verbosity" to listOf("2")), read("-v2"))
        assertEquals(mapOf("verbosity" to listOf("2")), read("--verbosity 2"))
        assertEquals(mapOf("exclude" to listOf("a", "b"), "tags" to listOf("x", "y")), read("-e a --tag x y -e b"))
        assertEquals(mapOf("loud" to listOf("", "")), read("-l -l"))
        assertEquals(mapOf("database" to listOf("other")), read("--data other"), "an unambiguous beginning, as argparse allows")
    }

    @Test
    fun `a half-typed option is filled in where it is`() {
        assertEquals(emptyMap<String, List<String>>(), read("--database"))
        assertEquals("--database x", write("--database", database, "x"))
        assertEquals("--database --fake", write("--database --fake", tags))
        assertEquals("--fake", write("--database --fake", database))
    }

    @Test
    fun `what it cannot place is kept as it is`() {
        assertEquals("--nope a b --fake", write("--nope a b", fake, ""))
        assertEquals("polls --nope a" to emptyList<String>(), positionals("--nope a", label to listOf("polls")))
        assertEquals("a b c", write("a b c", fake))
        assertEquals("x b c" to emptyList<String>(), positionals("a b c", label to listOf("x"), migration to listOf("b")))
        // dbshell's own form: everything from -- is the database client's
        assertEquals("--database other -- -c select", write("-- -c select", database, "other"))
        assertEquals(emptyMap<String, List<String>>(), read("-- --fake polls"))
    }

    @Test
    fun `an option is written where it was and after the rest when it is new`() {
        assertEquals("polls --fake", write("polls", fake, ""))
        assertEquals("polls", write("polls --fake", fake))
        assertEquals("polls --verbosity 3 --fake", write("polls -v2 --fake", verbosity, "3"))
        assertEquals("--exclude a --exclude b", write("", exclude, "a", "b"))
        assertEquals("--tag x y", write("--tag z", tags, "x", "y"))
        assertEquals("-l -l", write("-l", loud, "", ""))
    }

    @Test
    fun `flags in one word are each their own`() {
        val reverse = argument("reverse", ByDjangoArgument.FLAG, "-r", "--reverse")
        val debug = argument("debug", ByDjangoArgument.FLAG, "-d", "--debug-sql")
        val test = ByManageArguments(listOf(reverse, debug))
        assertEquals(setOf(reverse, debug), test.read(listOf("-rd")).keys)
        assertEquals(listOf("-d"), test.write(listOf("-rd"), reverse, emptyList()))
    }

    /** `showmigrations`: `--list` and `--plan` are both the argument `format`. */
    @Test
    fun `two arguments of one name are two`() {
        val list = argument("format", ByDjangoArgument.FLAG, "-l", "--list")
        val plan = argument("format", ByDjangoArgument.FLAG, "-p", "--plan")
        val show = ByManageArguments(listOf(list, plan))
        assertEquals(setOf(plan), show.read(listOf("--plan")).keys)
        assertEquals(listOf("--plan"), show.write(listOf("--plan"), list, emptyList()))
    }

    @Test
    fun `a positional waits for the one before it and is not lost`() {
        assertEquals("polls 0002 --fake" to emptyList<String>(), positionals("polls --fake", label to listOf("polls"), migration to listOf("0002")))
        assertEquals("" to listOf("migration_name"), positionals("", label to emptyList(), migration to listOf("0002")))
        assertEquals("--fake" to listOf("migration_name"), positionals("polls 0002 --fake", label to emptyList(), migration to listOf("0002")))
    }

    /** `squashmigrations app_label [start_migration_name] migration_name`. */
    @Test
    fun `a required positional takes its word from an optional one before it`() {
        val app = label.copy(required = true)
        val start = ByDjangoArgument("start", kind = ByDjangoArgument.VALUE)
        val end = ByDjangoArgument("end", kind = ByDjangoArgument.VALUE, required = true)
        val squash = ByManageArguments(listOf(app, start, end))
        assertEquals(mapOf(app to listOf("polls"), end to listOf("0004")), squash.read(listOf("polls", "0004")))
        assertEquals(listOf("0001"), squash.read(listOf("polls", "0001", "0004"))[start])
        assertEquals("polls 0004" to emptyList<String>(), positionals("polls", app to listOf("polls"), start to emptyList(), end to listOf("0004"), of = squash))
        assertEquals(mapOf(app to listOf("polls")), squash.read(listOf("polls")))
    }

    @Test
    fun `a value that reads as an option is attached`() {
        assertEquals("--database=-x", write("", database, "-x"))
        assertEquals(mapOf("database" to listOf("-x")), read("--database=-x"))
    }

    @Test
    fun `what the form writes it reads back`() {
        for ((argument, values) in listOf(database to listOf("a b"), fake to listOf(""), exclude to listOf("a", "b"), tags to listOf("x", "y"), loud to listOf("", ""))) {
            val written = arguments.write(listOf("polls", "--unknown"), argument, values)
            assertEquals(values, arguments.read(written)[argument], "$written")
        }
    }
}
