package dev.basedpython.pycharm.debug

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * What the debuggee reports back to the IDE, written as JSON by the `sitecustomize.py` bootstrap
 * (`/debug/sitecustomize.py`) to the path named by `BASEDPYTHON_DEBUG_INFO_OUT`.
 *
 * The file doubles as the readiness signal: it is written — atomically, via a temp file and a
 * rename — only once `debugpy.listen()` has returned, so its appearance means the port is accepting
 * connections. Polling for a file rather than for the port is what lets a failure be *reported*
 * instead of merely timing out: a debuggee that cannot import `debugpy` writes
 * [STATUS_ERROR] with a message naming the interpreter, and the IDE can say so.
 */
data class ByDebuggeeInfo(
    val status: String? = null,
    val port: Int = 0,
    /** `sys.executable` of the debuggee — the interpreter `by run` actually chose. */
    val python: String? = null,
    /** `by run`'s temp directory: the transpiled output and `_by_sourcemap.py` live here. */
    val runDir: String? = null,
    /**
     * `by run`'s runner, `_by_runner.py` in [runDir], as python names it in the runner's frames —
     * the path as the program was started with it, not resolved through `/var` -> `/private/var`.
     */
    val runner: String? = null,
    /** Present when [status] is [STATUS_ERROR], and as a warning alongside [STATUS_LISTENING]. */
    val message: String? = null,
    /**
     * Every field is nullable and defaulted because Gson builds instances without running the
     * constructor: an absent key leaves `null` behind whatever a Kotlin default declares, so
     * anything not marked nullable here would be a type-system lie waiting to throw. The error
     * report, for one, carries no `collisions` at all.
     */
    val collisions: List<ByGeneratedCollision>? = null,
) {
    val isListening: Boolean get() = status == STATUS_LISTENING

    /**
     * debugpy's `attach` arguments for this program: [provided], with [runner]'s frames left out of
     * the stack and of stepping, as debugpy leaves out `runpy`'s under `python -m`.
     *
     * `by run` documents the runner's frames as the outermost of the main thread and by's alone, to
     * be treated as the launcher's. Left in, they were the program's to pydevd, which counts any
     * file outside the environment as the user's: the stack ended in three of them, and Step Over
     * from the program's last line went on through `_by_runner.py` three times before the program
     * ended, where a `.py` run ends at once. A pydevd rule with no wildcard matches a frame's file by
     * equality, so the path is the one python reports for the runner, exactly.
     */
    fun attachArguments(provided: Map<String, Any?>): Map<String, Any?> {
        val runner = runner ?: return provided
        return provided + (RULES to listOf(mapOf(RULE_PATH to runner, RULE_INCLUDE to false)))
    }

    /** Collisions with at least two real sources; anything malformed is ignored. */
    val realCollisions: List<ByGeneratedCollision>
        get() = collisions.orEmpty().filter { it.sources.orEmpty().size > 1 }

    companion object {
        const val STATUS_LISTENING: String = "listening"
        const val STATUS_ERROR: String = "error"

        /** debugpy's `attach` key for which files are the user's, and the keys of one rule. */
        const val RULES: String = "rules"
        const val RULE_PATH: String = "path"
        const val RULE_INCLUDE: String = "include"

        /** Parses the bootstrap's JSON, or returns `null` for anything that is not valid JSON. */
        fun parse(json: String): ByDebuggeeInfo? =
            try {
                Gson().fromJson(json, ByDebuggeeInfo::class.java)
            } catch (_: JsonSyntaxException) {
                null
            }
    }
}

/**
 * One generated file that more than one `.by` source was transpiled to.
 *
 * `by run` writes them in turn, so the last one wins and every earlier source is silently absent
 * from the run — its code never executes and no breakpoint in it can ever bind. Invisible in
 * `SOURCEMAP` itself, which is a dict literal whose duplicate key has already collapsed; the
 * bootstrap recovers it by re-reading the file as a syntax tree.
 */
data class ByGeneratedCollision(
    val generated: String? = null,
    /** In write order, so the last is the one that survived. */
    val sources: List<String>? = null,
)
