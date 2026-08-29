package dev.basedpython.pycharm.debug.bpd

import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger

/**
 * What [ByBpdWrapper] and `bpd` between them wrote to the record file.
 *
 * Two things the IDE cannot know before `by run` has started: **what to launch**, because the temp
 * directory is chosen inside `by run`, and **where to connect**, because the port `bpd` really
 * bound and the token it minted are decided in the adapter process.
 *
 * Parsing is pure and total: every way the file can be incomplete answers [Incomplete] naming what
 * was missing, rather than a null the caller has to guess about. A debug session that will not
 * start is the one moment a user can inspect nothing, so "it did not work" is the outcome this
 * exists to rule out.
 */
sealed interface ByBpdRecord {

    /** Everything is here and the session can start. */
    data class Ready(
        /** The directory `by run` transpiled into, and the program's working directory. */
        val cwd: String,
        /** `_by_runner.py`, the module, and whatever the user passed after it. */
        val argv: List<String>,
        /** Where `bpd dap` is listening. */
        val host: String,
        val port: Int,
        /** The header a client must present on its first message, and its value. */
        val tokenHeader: String,
        val token: String,
    ) : ByBpdRecord

    /**
     * The file exists and is not finished, or is not what this reads.
     *
     * [why] is written for the user rather than for a log: it is what a failed debug start puts in
     * front of them, and the only thing they will have.
     */
    data class Incomplete(val why: String) : ByBpdRecord

    companion object {

        private val LOG = Logger.getInstance(ByBpdRecord::class.java)

        /**
         * The tree `by run` chose, out of a record file that may still be half written.
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
        fun buildDirectoryOf(record: java.nio.file.Path): String? =
            try {
                record.takeIf { java.nio.file.Files.isReadable(it) }
                    ?.let { java.nio.file.Files.readString(it) }
                    ?.lineSequence()
                    ?.firstOrNull { it.startsWith(ByBpdWrapper.CWD_PREFIX) }
                    ?.removePrefix(ByBpdWrapper.CWD_PREFIX)
                    ?.takeIf { it.isNotBlank() }
            } catch (e: java.io.IOException) {
                LOG.info("could not read the bpd record at $record", e)
                null
            }

        /**
         * Read a record, or say what is missing from it.
         *
         * The wrapper writes its lines and `bpd` appends its announcement, so a file with only the
         * first half is the ordinary state a moment after the program starts — that is
         * [Incomplete] and the caller polls again, not a failure.
         */
        fun parse(text: String): ByBpdRecord {
            var cwd: String? = null
            val argv = mutableListOf<String>()
            var announcement: String? = null

            for (line in text.lineSequence()) {
                when {
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
            if (argv.isEmpty()) {
                return Incomplete("the wrapper recorded no program to run")
            }
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
