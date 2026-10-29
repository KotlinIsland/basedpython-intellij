package dev.basedpython.pycharm.terminal

import com.google.gson.JsonParser
import com.intellij.openapi.util.Key
import com.intellij.terminal.completion.spec.ShellArgumentSpec
import com.intellij.terminal.completion.spec.ShellCommandResult
import com.intellij.terminal.completion.spec.ShellCommandSpec
import com.intellij.terminal.completion.spec.ShellDataGeneratorProcessBuilder
import com.intellij.terminal.completion.spec.ShellFileInfo
import com.intellij.terminal.completion.spec.ShellName
import com.intellij.terminal.completion.spec.ShellOptionSpec
import com.intellij.terminal.completion.spec.ShellRuntimeContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `by` typed in the terminal is completed from `by/commandLine`, recorded from `by` caabe9cac in
 * `by-replies/command-line.json`: every command, option and positional is `by`'s answer, and a
 * value is completed by its kind.
 */
class ByShellCommandSpecsProviderTest {

    private val recorded: ByCommand by lazy {
        val stream = checkNotNull(javaClass.getResourceAsStream("/by-replies/command-line.json"))
        checkNotNull(ByCommand.of(stream.reader().use(JsonParser::parseReader)))
    }

    /** A terminal with nothing behind it: generating from the answer asks it nothing. */
    private object NoTerminal : ShellRuntimeContext {
        override val currentDirectory: String = "/"
        override val envVariables: Map<String, String> = emptyMap()
        override val commandTokens: List<String> = listOf("by")
        override val typedPrefix: String = ""
        override val shellName: ShellName = ShellName("zsh")
        override suspend fun runShellCommand(command: String): ShellCommandResult = error("no shell")
        override suspend fun createProcessBuilder(executable: String): ShellDataGeneratorProcessBuilder = error("no shell")
        override suspend fun listDirectoryFiles(path: String): List<ShellFileInfo> = error("no shell")
        override fun <T> getUserData(key: Key<T>): T? = null
    }

    private val spec = ByShellCommandSpecsProvider.spec { recorded }

    private fun subcommands(command: ShellCommandSpec): List<ShellCommandSpec> =
        runBlocking { command.subcommandsGenerator.generate(NoTerminal) }

    private fun subcommand(command: ShellCommandSpec, name: String) = subcommands(command).single { it.name == name }

    private fun suggestions(argument: ShellArgumentSpec): List<String> =
        runBlocking { argument.generators.flatMap { it.generate(NoTerminal) }.map { it.name } }

    private fun option(command: ShellCommandSpec, name: String): ShellOptionSpec = command.options.single { it.name == name }

    @Test
    fun `the answer is read whole, every command and argument by's help shows`() {
        val run = recorded.subcommands.single { it.name == "run" }
        assertEquals(listOf(ByValue.Module("MODULE"), ByValue.Text("ARGS")), run.positionals.map { it.value })
        assertTrue(run.positionals.last().trailing)
        val check = recorded.subcommands.single { it.name == "check" }
        val verbose = check.options.single { it.long == "verbose" }
        assertEquals(listOf("--verbose", "-v"), verbose.names)
        assertTrue(verbose.repeatable)
        assertEquals(null, verbose.value)
        val explain = recorded.subcommands.single { it.name == "explain" }
        assertEquals(ByValue.Rule("RULE"), explain.subcommands.single { it.name == "rule" }.positionals.single().value)
    }

    @Test
    fun `the commands are by's, with its help as their description`() {
        val commands = subcommands(spec)
        assertEquals(recorded.subcommands.map { it.name }, commands.map { it.name })
        assertTrue(listOf("run", "repl", "restage").all { name -> commands.any { it.name == name } })
        assertEquals(recorded.subcommands.single { it.name == "run" }.help, commands.single { it.name == "run" }.description)
    }

    @Test
    fun `the root's options come from the answer too`() {
        val options = runBlocking { spec.allOptionsGenerator.generate(NoTerminal) }.map { it.name }
        assertTrue("--version" in options && "-V" in options, "options were $options")
    }

    @Test
    fun `by run takes a module, then the program's arguments, which nothing completes`() {
        val (module, args) = subcommand(spec, "run").arguments
        assertTrue(module.isOptional)
        assertFalse(module.isVariadic)
        assertEquals(1, module.generators.size, "a module is completed from by/runModules")
        assertTrue(args.isVariadic)
        assertFalse(args.optionsCanBreakVariadicArg, "after the module, an option is the program's")
        assertTrue(args.generators.isEmpty())
    }

    @Test
    fun `a nested command is by's, and a rule is completed`() {
        val rule = subcommand(subcommand(spec, "explain"), "rule")
        assertEquals(1, rule.arguments.single().generators.size)
    }

    @Test
    fun `a choice is completed with its choices, a repeatable flag can repeat, and a text value completes nothing`() {
        val check = subcommand(spec, "check")
        val format = option(check, "--output-format").arguments.single()
        assertEquals(
            recorded.subcommands.single { it.name == "check" }.options.single { it.long == "output-format" }
                .value.let { (it as ByValue.Choice).choices.map(ByValue.Named::name) },
            suggestions(format),
        )
        assertEquals(0, option(check, "--verbose").repeatTimes)
        assertEquals(1, option(check, "--fix").repeatTimes)
        assertTrue(option(check, "--python-platform").arguments.single().generators.isEmpty())
    }

    @Test
    fun `a project whose by does not answer completes no by command`() {
        val nothing = ByShellCommandSpecsProvider.spec { null }
        assertTrue(subcommands(nothing).isEmpty())
        assertNotNull(nothing.name)
    }
}
