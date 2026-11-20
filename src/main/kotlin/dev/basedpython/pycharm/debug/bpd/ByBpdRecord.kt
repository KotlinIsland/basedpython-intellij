package dev.basedpython.pycharm.debug.bpd

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import dev.basedpython.pycharm.debug.ByBpdEvents

/**
 * What [ByBpdWrapper] and `bpd` between them wrote to the record file.
 *
 * Two things the IDE cannot know before `by run` has started: **what to launch**, because the
 * interpreter and the temp directory are chosen inside `by run`, and **where to connect**, because
 * the port `bpd` really bound and the token it minted are decided in the adapter process.
 *
 * Parsing is pure and total: every way the file can be incomplete answers [Incomplete] naming what
 * was missing, rather than a null the caller has to guess about. A debug session that will not
 * start is the one moment a user can inspect nothing, so "it did not work" is the outcome this
 * exists to rule out.
 */
sealed interface ByBpdRecord {

    /**
     * What `by run` chose, which the `launch` request sends back: everything a session needs of the
     * record once it is connected.
     */
    sealed interface Launch : ByBpdRecord {
        /** The interpreter `by run` chose, which bpd starts the program on. */
        val python: String
        /** The program's working directory: the one `by run` started it in. */
        val cwd: String
        /** `_by_runner.py`, by its path, then the module and whatever the user passed after it. */
        val argv: List<String>

        /**
         * The `launch` request's arguments: [base], with the interpreter, the program and its
         * arguments as `by run` really chose them.
         *
         * Taken from here rather than from the run configuration, because only `by run` knows them:
         * it discovers the interpreter, chooses the module when the configuration names none
         * (`run.main`), decides what goes after the runner, and names the runner by a path in a
         * directory it has only just made. A launch request built from the configuration was a
         * second guess at all four.
         *
         * And what this plugin reads of bpd's events, and whether to stream the recompositions from
         * the first line ([watchRecompositions]): the launch is the one request that cannot reach
         * bpd after the program has started, because it is what starts it, so nothing said here has
         * to overtake the platform's own `setBreakpoints` and `configurationDone`.
         */
        fun launchArguments(base: Map<String, Any?>, watchRecompositions: Boolean): Map<String, Any?> =
            base + mapOf(
                PYTHON to python,
                PROGRAM to argv.first(),
                ARGS to argv.drop(1),
                UNDERSTANDS to ByBpdEvents.UNDERSTOOD,
                WATCH_RECOMPOSITIONS to watchRecompositions,
            )
    }

    /**
     * What `by run` chose, for a session `bpd dap --connect` dials into: there is no announcement,
     * since the IDE chose the address and the token itself.
     */
    data class Chosen(
        override val python: String,
        override val cwd: String,
        override val argv: List<String>,
    ) : Launch

    /** Everything is here and the session can start. */
    data class Ready(
        override val python: String,
        override val cwd: String,
        override val argv: List<String>,
        /** Where `bpd dap` is listening. */
        val host: String,
        val port: Int,
        /** The header a client must present on its first message, and its value. */
        val tokenHeader: String,
        val token: String,
    ) : Launch

    /**
     * The file exists and is not finished, or is not what this reads.
     *
     * [why] is written for the user rather than for a log: it is what a failed debug start puts in
     * front of them, and the only thing they will have.
     */
    data class Incomplete(val why: String) : ByBpdRecord

    /**
     * The wrapper found no `bpd` — not beside `by`, not beside [python], not on `PATH` — and exited
     * without starting the program. Final, unlike [Incomplete]: nothing more will be written.
     */
    data class NoBpd(val python: String) : ByBpdRecord

    companion object {

        private val LOG = Logger.getInstance(ByBpdRecord::class.java)

        /** The `bpd_dap::Configuration` keys [Launch.launchArguments] fills in. */
        const val PYTHON: String = "python"
        const val PROGRAM: String = "program"
        const val ARGS: String = "args"

        /** And the two that are this plugin's rather than the program's. */
        const val UNDERSTANDS: String = "understands"
        const val WATCH_RECOMPOSITIONS: String = "watchRecompositions"

        /**
         * The directory of the runner `by run` started the program as, out of a record file that may
         * still be half written: the top of the tree `by run` staged, or for a run in place the run
         * directory in `by`'s cache. Its map, `_by_sourcemap.py`, is beside the runner either way.
         *
         * The runner's directory and not the working directory the record also names: the program
         * runs where the user started it, which is neither.
         *
         * The wrapper writes its lines before `bpd` appends its announcement, so the directory is
         * readable from the moment the program starts — earlier than a whole [Ready] is. That
         * matters because this is asked long after the launch, when nothing is waiting for
         * anything: a session that is running has a complete record, and one that never started has
         * no directory to give.
         *
         * Null rather than an exception for every way it can be absent. A missing directory costs
         * the hot reload button; it must never cost the session that asked.
         */
        fun runnerDirectoryOf(record: java.nio.file.Path): String? =
            try {
                record.takeIf { java.nio.file.Files.isReadable(it) }
                    ?.let { java.nio.file.Files.readString(it) }
                    ?.let(::runnerDirectoryIn)
            } catch (e: java.io.IOException) {
                LOG.info("could not read the bpd record at $record", e)
                null
            }

        /**
         * [runnerDirectoryOf] the record's [text]: the directory of its first argument, the runner,
         * against the working directory when `by run` named it relatively.
         */
        internal fun runnerDirectoryIn(text: String): String? {
            val lines = text.lineSequence().toList()
            val runner = lines.firstOrNull { it.startsWith(ByBpdWrapper.ARG_PREFIX) }
                ?.removePrefix(ByBpdWrapper.ARG_PREFIX)
                ?.takeIf { it.isNotBlank() }
                ?: return null
            val cwd = lines.firstOrNull { it.startsWith(ByBpdWrapper.CWD_PREFIX) }
                ?.removePrefix(ByBpdWrapper.CWD_PREFIX)
                ?.takeIf { it.isNotBlank() }
            return try {
                val path = java.nio.file.Path.of(runner)
                val absolute = if (path.isAbsolute) path else java.nio.file.Path.of(cwd ?: return null).resolve(path)
                absolute.normalize().parent?.toString()
            } catch (_: java.nio.file.InvalidPathException) {
                null
            }
        }

        /**
         * Read a record, or say what is missing from it.
         *
         * The wrapper writes its lines and `bpd` appends its announcement, so a file with only the
         * first half is the ordinary state a moment after the program starts — that is
         * [Incomplete] and the caller polls again, not a failure. A record of a session `bpd dap
         * --connect` dials into has no announcement, and is complete without one when [announced]
         * is false.
         */
        fun parse(text: String, announced: Boolean = true): ByBpdRecord {
            var python: String? = null
            var cwd: String? = null
            val argv = mutableListOf<String>()
            var announcement: String? = null

            for (line in text.lineSequence()) {
                when {
                    line.startsWith(ByBpdWrapper.NO_BPD_PREFIX) ->
                        return NoBpd(line.removePrefix(ByBpdWrapper.NO_BPD_PREFIX))

                    line.startsWith(ByBpdWrapper.PYTHON_PREFIX) ->
                        python = line.removePrefix(ByBpdWrapper.PYTHON_PREFIX)

                    line.startsWith(ByBpdWrapper.CWD_PREFIX) ->
                        cwd = line.removePrefix(ByBpdWrapper.CWD_PREFIX)

                    line.startsWith(ByBpdWrapper.ARG_PREFIX) ->
                        argv.add(line.removePrefix(ByBpdWrapper.ARG_PREFIX))

                    // bpd's announcement is the only json in the file
                    line.startsWith("{") -> announcement = line
                }
            }

            if (cwd == null) {
                return Incomplete(
                    "the wrapper has not said which directory `by run` transpiled into yet",
                )
            }
            if (python == null) {
                return Incomplete("the wrapper has not said which interpreter `by run` chose yet")
            }
            if (argv.isEmpty()) {
                return Incomplete("the wrapper recorded no program to run")
            }
            if (!announced) return Chosen(python = python, cwd = cwd, argv = argv)
            val listening = announcement
                ?: return Incomplete("`bpd dap` has not said where it is listening yet")

            val json = try {
                JsonParser.parseString(listening).asJsonObject.getAsJsonObject("listening")
            } catch (_: RuntimeException) {
                return Incomplete(
                    "`bpd dap` wrote `$listening`, which is not the `{\"listening\":{…}}` " +
                        "announcement a client reads the port and token out of",
                )
            } ?: return Incomplete(
                "`bpd dap` wrote json with no `listening` object in it, so there is no port to " +
                    "connect to",
            )

            return try {
                Ready(
                    python = python,
                    cwd = cwd,
                    argv = argv,
                    host = json.get("host").asString,
                    port = json.get("port").asInt,
                    tokenHeader = json.get("header").asString,
                    token = json.get("token").asString,
                )
            } catch (_: RuntimeException) {
                Incomplete(
                    "`bpd dap` announced `$listening`, and `host`, `port`, `header` and `token` " +
                        "are all needed to reach it",
                )
            }
        }
    }
}
