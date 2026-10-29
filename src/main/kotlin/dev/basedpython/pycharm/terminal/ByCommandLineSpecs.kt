package dev.basedpython.pycharm.terminal

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClient
import com.intellij.terminal.completion.spec.ShellCommandSpec
import com.intellij.terminal.completion.spec.ShellCompletionSuggestion
import com.intellij.terminal.completion.spec.ShellRuntimeContext
import dev.basedpython.pycharm.lsp.ByAnswer
import dev.basedpython.pycharm.lsp.ByServerStart
import dev.basedpython.pycharm.lsp.awaitBy
import dev.basedpython.pycharm.lsp.awaitByServer
import dev.basedpython.pycharm.lsp.awaitingAgain
import dev.basedpython.pycharm.lsp.ext.ByRunModulesParams
import dev.basedpython.pycharm.lsp.ext.ByServerExtensions
import dev.basedpython.pycharm.lsp.isMethodNotFound
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpec
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecConflictStrategy
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecInfo
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCommandSpecsProvider
import org.jetbrains.plugins.terminal.block.completion.spec.ShellCompletionSuggestion
import org.jetbrains.plugins.terminal.block.completion.spec.ShellDataGenerators
import org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellArgumentContext
import org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellChildCommandsContext
import org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellChildOptionsContext
import org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellCommandContext
import org.jetbrains.plugins.terminal.block.completion.spec.project

/**
 * Completes `by` typed in the terminal from `by`'s own description of its command line,
 * `by/commandLine` ([ByCommand]): `by r` offers `run`, `repl` and `restage`, `by run ` the project's
 * modules, `by explain rule ` its rules, `by check --output-format ` the formats.
 *
 * Nothing about `by`'s command line is written here. The spec is one command, `by`, whose options
 * and subcommands are generated from the answer of the project's `by` when a completion asks for
 * them, so a command `by` adds is completed by the `by` that has it, and only by that one. A value is
 * completed by its kind: a module from `by/runModules`, a rule from `by/rules` (and `all`), a path
 * from the file system, a choice from its choices; text, and an argument that takes the rest of the
 * line — a program's own arguments after `by run MODULE` — not at all.
 *
 * The root's own positionals are the one thing not completed: the platform's spec fixes a command's
 * arguments before anything runs, and only subcommands and options can be generated. `by`'s root
 * takes none.
 *
 * A project with no `by` running, or a `by` from before the request, completes nothing.
 */
internal class ByShellCommandSpecsProvider : ShellCommandSpecsProvider {

    override fun getCommandSpecs(): List<ShellCommandSpecInfo> =
        listOf(ShellCommandSpecInfo.create(spec(), ShellCommandSpecConflictStrategy.DEFAULT))

    companion object {
        const val BY: String = "by"

        /** The `by` command, its options and subcommands asked of [commandLine] for the terminal's project. */
        fun spec(commandLine: suspend (ShellRuntimeContext) -> ByCommand? = ::commandLineOf): ShellCommandSpec =
            ShellCommandSpec(BY) {
                dynamicOptions { context -> commandLine(context)?.options?.forEach { option(it) } }
                subcommands { context -> commandLine(context)?.subcommands?.forEach { subcommand(it) } }
            }

        private suspend fun commandLineOf(context: ShellRuntimeContext): ByCommand? =
            ByCommandLines.getInstance(context.project).commandLine()
    }
}

private fun ShellChildCommandsContext.subcommand(command: ByCommand) {
    subcommand(command.name, *command.aliases.toTypedArray()) { describe(command) }
}

/** [command]'s options, positionals and subcommands, in a context that already has its answer. */
private fun ShellCommandContext.describe(command: ByCommand) {
    command.help?.let { description(it) }
    command.options.forEach { option(it) }
    command.positionals.forEach { positional ->
        argument {
            positional.value.name?.let { displayName(it) }
            if (!positional.required) optional()
            if (positional.repeatable || positional.trailing) variadic()
            // what follows is the program's, options included, and `by` does not interpret it
            if (positional.trailing) optionsCantBreakVariadicArg() else complete(positional.value)
        }
    }
    if (command.subcommands.isNotEmpty()) {
        subcommands { _ -> command.subcommands.forEach { subcommand(it) } }
    }
}

private fun ShellCommandContext.option(option: ByOption) {
    option(*option.names.toTypedArray()) { describe(option) }
}

private fun ShellChildOptionsContext.option(option: ByOption) {
    option(*option.names.toTypedArray()) { describe(option) }
}

private fun org.jetbrains.plugins.terminal.block.completion.spec.dsl.ShellOptionContext.describe(option: ByOption) {
    option.help?.let { description(it) }
    if (option.repeatable) repeatTimes(0)
    option.value?.let { value ->
        argument {
            value.name?.let { displayName(it) }
            complete(value)
        }
    }
}

private fun ShellArgumentContext.complete(value: ByValue) {
    when (value) {
        is ByValue.Module -> suggestions { context -> ByCommandLines.getInstance(context.project).modules().map(::ShellCompletionSuggestion) }
        is ByValue.Rule -> suggestions { context ->
            (ByCommandLines.getInstance(context.project).rules() + ALL_RULES).map(::ShellCompletionSuggestion)
        }
        // directories are offered for every hint: a file is reached through them
        is ByValue.Path -> suggestions(ShellDataGenerators.fileSuggestionsGenerator(onlyDirectories = value.hint == DIRECTORY))
        is ByValue.Choice -> suggestions { _ ->
            value.choices.map { choice -> ShellCompletionSuggestion(choice.name) { choice.help?.let { description(it) } } }
        }
        is ByValue.Text -> Unit
    }
}

/** `by explain rule all`, `--error all`: every rule, which `by/rules` does not list. */
private const val ALL_RULES = "all"
private const val DIRECTORY = "directory"

/**
 * What the project's `by` answers for the terminal: its command line, asked once per server since
 * the answer is the command line of the `by` the server is, and its modules and rules, asked when a
 * completion needs them.
 */
@Service(Service.Level.PROJECT)
internal class ByCommandLines(private val project: Project) {

    private class Asked(val client: LspClient, val commandLine: ByCommand?)

    private val asking = Mutex()

    @Volatile
    private var asked: Asked? = null

    /** The running `by`'s command line; null when no `by` runs or it does not answer the request. */
    suspend fun commandLine(): ByCommand? {
        val client = client() ?: return null
        asked?.takeIf { it.client === client }?.let { return it.commandLine }
        return asking.withLock {
            asked?.takeIf { it.client === client }?.let { return@withLock it.commandLine }
            when (val answer = ask(client)) {
                is Answered.Known -> answer.commandLine.also { asked = Asked(client, it) }
                // asked again next time: a timeout says nothing about the server
                Answered.Failed -> null
            }
        }
    }

    private sealed interface Answered {
        /** What the server said, null for a `by` without the request, which it will not grow. */
        class Known(val commandLine: ByCommand?) : Answered
        data object Failed : Answered
    }

    private suspend fun ask(client: LspClient): Answered {
        var unknown = false
        val answer = awaitingAgain<com.google.gson.JsonObject>("by/commandLine", LspClient.DEFAULT_REQUEST_TIMEOUT_MS.toLong()) { sent ->
            try {
                client.sendRequest { (it as ByServerExtensions).commandLine().also(sent) }
            } catch (e: Exception) {
                if (isMethodNotFound(e)) unknown = true
                throw e
            }
        }
        return when {
            unknown -> Answered.Known(null)
            answer is ByAnswer.Answer -> Answered.Known(ByCommand.of(answer.value))
            answer == ByAnswer.None -> Answered.Known(null)
            else -> Answered.Failed.also { LOG.info("by/commandLine was not answered; the terminal completes no `by` until it is") }
        }
    }

    /** Each module `by/runModules` names, across the projects the session holds. */
    suspend fun modules(): List<String> {
        val client = client() ?: return emptyList()
        val reply = client.awaitBy("by/runModules") { (it as ByServerExtensions).runModules(ByRunModulesParams()) }
        return (reply as? ByAnswer.Answer)?.value?.projects.orEmpty()
            .flatMap { project -> project.modules.mapNotNull { it.module } }
            .distinct()
    }

    /** Each rule `by/rules` names. */
    suspend fun rules(): List<String> {
        val client = client() ?: return emptyList()
        val reply = client.awaitBy("by/rules") { (it as ByServerExtensions).rules() }
        return (reply as? ByAnswer.Answer)?.value.orEmpty().map { it.name }.filter { it.isNotEmpty() }
    }

    /**
     * The project's `by` once it has initialized: one already starting is waited for, and one that
     * is switched on and not running is asked to start, completing nothing this time rather than
     * holding the completion until it is up.
     */
    private suspend fun client(): LspClient? =
        (awaitByServer(project, waitForNewStart = false) as? ByServerStart.Running)?.client

    companion object {
        private val LOG = Logger.getInstance(ByCommandLines::class.java)

        fun getInstance(project: Project): ByCommandLines = project.service()
    }
}
