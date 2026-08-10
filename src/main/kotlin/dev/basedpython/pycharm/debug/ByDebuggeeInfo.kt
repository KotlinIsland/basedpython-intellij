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
    /** Present when [status] is [STATUS_ERROR], and as a warning alongside [STATUS_LISTENING]. */
    val message: String? = null,
    val files: List<ByGeneratedFile>? = null,
) {
    val isListening: Boolean get() = status == STATUS_LISTENING

    /**
     * Every field is nullable and defaulted because Gson builds instances without running the
     * constructor: an absent key leaves `null` behind whatever a Kotlin default declares, so
     * anything not marked nullable here would be a type-system lie waiting to throw. The error
     * report, for one, carries no `files` at all.
     */
    val mappedFiles: List<ByGeneratedFile> get() = files.orEmpty()

    companion object {
        const val STATUS_LISTENING: String = "listening"
        const val STATUS_ERROR: String = "error"

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
 * One transpiled file, exactly as `_by_sourcemap.py` records it.
 *
 * [lines] is indexed by *generated* line (0-based) and holds the 0-based `.by` line that line came
 * from, or `null` for emitted prelude with no source. It is deliberately carried across unchanged
 * and inverted on this side — see [ByLineMapping.invert].
 */
data class ByGeneratedFile(
    val source: String? = null,
    val generated: String? = null,
    val lines: List<Int?>? = null,
)
