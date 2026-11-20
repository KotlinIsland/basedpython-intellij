package dev.basedpython.pycharm.debug

import kotlinx.serialization.json.JsonObject

/**
 * What a Reset Frame really did, as bpd reports it on `bpd/restarting`.
 *
 * bpd narrates a restart on the console to a client that has not named this event in the launch's
 * `understands`, and the narration is written for an agent: five sentences on a plain reset, each
 * prefixed with its stop number, three of them saying that nothing surprising happened. This plugin
 * names the event ([ByBpdEvents.UNDERSTOOD]) and says, itself, only what a person could not see —
 * see [report].
 *
 * Read field by field, for the reason [ByMoved] is: the body is bpd's `Restarted` serialised whole,
 * tagged `restarted`, and anything missing or of the wrong shape yields null rather than an
 * exception.
 */
internal sealed interface ByRestarting {

    /** The function of the frame that was asked to restart. */
    val function: String?

    /** Run again where it stood: the same frame object, back at its first line, still held. */
    data class Reset(
        override val function: String?,
        /** Whether it was inside a `with` or a `try`, whose cleanup the reset did not run. */
        val insideABlock: Boolean,
    ) : ByRestarting

    /** Forced out and its caller rewound to the call, so the call builds a fresh frame. */
    data class Arranged(
        override val function: String?,
        val caller: String?,
        val callerLine: Int?,
        /** Whether the forced-out frame was inside a `with` or a `try`, whose cleanup did not run. */
        val insideABlock: Boolean,
        /** Names on the caller's line that hold the forced return's value until the call returns again. */
        val disturbed: List<String>,
        /** Names in the forced-out frame that can run code as it is released: a `__del__`, a generator. */
        val finalising: List<String>,
        /** Breakpoints on either line the restart moves to, which will not fire for this pass. */
        val unannounced: List<Int>,
    ) : ByRestarting

    /** The frames above it are being forced out, and it is reset once they have returned. */
    data class Unwinding(
        override val function: String?,
        /** The frames forced out that were inside a `with` or a `try`, whose cleanup did not run. */
        val leftInsideABlock: List<String>,
    ) : ByRestarting

    /**
     * cpython would not move the frame to a return on any of its lines, so nothing restarted.
     *
     * Answered with `success`, which is why it has to be said: to the platform the request worked,
     * and without this line Reset Frame would look like a button that did nothing.
     */
    data class Refused(
        override val function: String?,
        /** cpython's own refusal, as `kind: message`. */
        val refusal: String?,
    ) : ByRestarting

    companion object {

        /** The event name, which is also what the launch's `understands` names back ([ByBpdEvents.UNDERSTOOD]). */
        const val EVENT: String = "bpd/restarting"

        /** Read an event body, or null when it is not one this can use. */
        fun parse(body: JsonObject?): ByRestarting? {
            val restarting = body?.obj("restarting") ?: return null
            val function = restarting.obj("frame")?.string("function")
            return when (restarting.string("restarted")) {
                "reset" -> Reset(function, insideABlock = restarting.bool("inside_a_block") == true)
                "arranged" -> Arranged(
                    function = function,
                    caller = restarting.obj("caller")?.string("function"),
                    callerLine = restarting.obj("caller")?.int("line"),
                    insideABlock = restarting.bool("inside_a_block") == true,
                    disturbed = restarting.strings("disturbed"),
                    finalising = restarting.strings("finalising"),
                    unannounced = restarting.ints("unannounced"),
                )
                "unwinding" -> Unwinding(
                    function = function,
                    leftInsideABlock = restarting.array("above").orEmpty().mapNotNull { it.objOrNull() }
                        .filter { it.bool("inside_a_block") == true }
                        .map { it.obj("at")?.string("function") ?: "a frame" },
                )
                "refused" -> Refused(function, refusal = restarting.obj("error")?.let(::describe))
                else -> null
            }
        }

        /** A `PythonError` as `kind: message`, the way [ByMoved] reads one. */
        private fun describe(error: JsonObject): String? {
            val kind = error.string("kind")
            val message = error.string("message")
            return when {
                kind == null -> message
                message.isNullOrEmpty() -> kind
                else -> "$kind: $message"
            }
        }
    }
}

/**
 * The line to put on the console about a restart, or null when there is nothing worth saying.
 *
 * A restart that did what was asked needs no line: the frame is on screen at its first line. What
 * does need one is what a person cannot see from there — a `with` or a `try` whose cleanup was
 * skipped, names in the caller holding a value the program never computed, finalisers run early,
 * breakpoints passed over — and a refusal, which the platform took for a success.
 *
 * Plain text, since the console is: names are bare rather than in backticks.
 */
internal fun ByRestarting.report(): String? {
    val name = function ?: "the frame"
    val notes = when (this) {
        is ByRestarting.Refused -> return "$name was not reset" + (refusal?.let { ": $it" } ?: "") +
            ". it is still where it was"

        is ByRestarting.Reset -> listOfNotNull(
            "the with or try it was inside did not clean up and will be entered again".takeIf { insideABlock },
        )

        is ByRestarting.Unwinding -> listOfNotNull(
            leftInsideABlock.takeIf { it.isNotEmpty() }
                ?.let { "${it.joinToString(", ")} left a with or try without cleaning up" },
        )

        is ByRestarting.Arranged -> listOfNotNull(
            "the with or try it was inside did not clean up".takeIf { insideABlock },
            disturbed.takeIf { it.isNotEmpty() }?.let {
                "${it.joinToString(", ")} in ${caller ?: "the caller"} hold" +
                    " a placeholder until $name returns again"
            },
            finalising.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} may have been finalised early" },
            unannounced.takeIf { it.isNotEmpty() }?.let { "breakpoint ${it.joinToString(", ")} will not fire this pass" },
        )
    }
    return notes.takeIf { it.isNotEmpty() }?.joinToString("; ", prefix = "reset $name: ")
}
