package dev.basedpython.pycharm.lsp

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import org.eclipse.lsp4j.InitializeResult

/**
 * Which flags of the commands the plugin starts `by` with — a console's `by repl`, a run's `by run` —
 * the project's `by` takes, as its language server says in its `initialize` answer.
 *
 * `by` answers under `capabilities.experimental`, each command by name and each flag by its long name
 * in camelCase (`by`'s `docs/basedpython/features/editor.md`, *what `by` takes on its command line*):
 * `{"repl": {"events": true, …}, "run": {"coverage": true, …}}`. A flag not named there, or an answer
 * with no `experimental` at all — a `by` from before it — is one this `by` does not take.
 *
 * Asked of the server rather than of `by repl --help`: the server is the `by` the project resolves, and
 * asking it starts nothing and parses no text written for people.
 */
internal class ByCommandFlags private constructor(private val experimental: JsonObject?) {

    /** Whether `by [command]` takes the flag named [flag] (camelCase: `sessionFile` is `--session-file`). */
    fun takes(command: String, flag: String): Boolean {
        val flags = experimental?.get(command)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val value = flags.get(flag) ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
    }

    companion object {
        /** What a `by` takes that has said nothing: no flag at all. */
        val NONE: ByCommandFlags = ByCommandFlags(null)

        /** What the `initialize` answer [result] says. */
        fun of(result: InitializeResult?): ByCommandFlags =
            ByCommandFlags(result?.capabilities?.experimental as? JsonObject)

        /**
         * What the project's running `by` says, starting it first when it is not running, as
         * [awaitByServer] does with [waitForNewStart]; [NONE] when no `by` answers.
         */
        suspend fun of(project: Project, waitForNewStart: Boolean): ByCommandFlags =
            when (val start = awaitByServer(project, waitForNewStart = waitForNewStart)) {
                is ByServerStart.Running -> of(start.client.initializeResult)
                is ByServerStart.Unavailable -> NONE
            }

        const val REPL: String = "repl"

        /** `by repl --events PORT`: what the session reads its standard input for. */
        const val REPL_EVENTS: String = "events"
    }
}
