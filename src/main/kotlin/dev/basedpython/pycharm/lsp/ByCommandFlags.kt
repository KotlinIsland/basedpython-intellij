package dev.basedpython.pycharm.lsp

import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.project.Project
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspServerState
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
 * asking it starts nothing and parses no text written for people. A `by` on a run target is not that
 * one, and is asked itself, with `by command-line-flags`, which prints the same table ([ofPrinted]).
 */
internal class ByCommandFlags private constructor(private val experimental: JsonObject?) {

    /** Whether `by [command]` takes the flag named [flag] (camelCase: `sessionFile` is `--session-file`). */
    fun takes(command: String, flag: String): Boolean {
        val flags = experimental?.get(command)?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val value = flags.get(flag) ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
    }

    /**
     * Whether `by run --in-place` runs a program against the project's own directories, its `.by`
     * modules imported from their transpiles in `by`'s run cache, rather than out of a tree staged
     * into a temporary directory. A `by` whose server also answers `by/retranspile`.
     */
    val runsInPlace: Boolean get() = takes(RUN, IN_PLACE)

    /** Whether `by repl --in-place` runs a session against the project's own directories. */
    val replsInPlace: Boolean get() = takes(REPL, IN_PLACE)

    companion object {
        /** What a `by` takes that has said nothing: no flag at all. */
        val NONE: ByCommandFlags = ByCommandFlags(null)

        /** What the `initialize` answer [result] says. */
        fun of(result: InitializeResult?): ByCommandFlags =
            ByCommandFlags(result?.capabilities?.experimental as? JsonObject)

        /**
         * What a `by` printed for `by command-line-flags` ([PRINT_COMMAND]): the table its server answers
         * `initialize` with, as json on one line. Anything but a json object says nothing.
         */
        fun ofPrinted(stdout: String): ByCommandFlags = ByCommandFlags(
            try {
                JsonParser.parseString(stdout) as? JsonObject
            } catch (_: JsonParseException) {
                null
            },
        )

        /**
         * What the project's running `by` says, starting it first when it is not running, as
         * [awaitByServer] does with [waitForNewStart]; [NONE] when no `by` answers.
         */
        suspend fun of(project: Project, waitForNewStart: Boolean): ByCommandFlags =
            when (val start = awaitByServer(project, waitForNewStart = waitForNewStart)) {
                is ByServerStart.Running -> of(start.client.initializeResult)
                is ByServerStart.Unavailable -> NONE
            }

        /**
         * What the project's running `by` says, from whichever thread a run is started on: on the EDT
         * under a modal, cancellable progress titled [title], where a `by` that is not running is asked
         * to start but not waited for, since a start cannot finish under a modal progress; elsewhere
         * it is started and waited for.
         */
        fun ofBlocking(project: Project, title: String): ByCommandFlags =
            byBlocking(project, title) { waitForNewStart -> of(project, waitForNewStart) }

        /**
         * What the project's `by` says if it is running, starting nothing and waiting for nothing:
         * [NONE] while none is. For what reads an answer `by` has already given, such as the tests a
         * running server reported.
         */
        fun ofRunning(project: Project): ByCommandFlags =
            LspClientManager.getInstance(project).getClients(ByLspServerSupportProvider::class.java)
                .firstOrNull { it.state == LspServerState.Running }
                ?.let { of(it.initializeResult) }
                ?: NONE

        /**
         * The hidden command that prints what [of] reads from `initialize`; a `by` from before it refuses
         * it, which is a `by` that says nothing ([NONE]).
         */
        const val PRINT_COMMAND: String = "command-line-flags"

        const val RUN: String = "run"

        const val REPL: String = "repl"

        /** `by run --in-place` and `by repl --in-place`; see [runsInPlace]. */
        const val IN_PLACE: String = "inPlace"

        /** How [IN_PLACE] is spelled on `by`'s command line. */
        const val IN_PLACE_FLAG: String = "--in-place"

        /** `by repl --events PORT`: what the session reads its standard input for. */
        const val REPL_EVENTS: String = "events"
    }
}
