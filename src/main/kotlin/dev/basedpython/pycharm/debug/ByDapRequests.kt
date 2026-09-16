package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapSessionContext
import com.jetbrains.dap.protocol.DapRequestFailedException
import com.jetbrains.dap.protocol.RequestType
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The requests this plugin sends that the base protocol does not have: pydevd's
 * `setPydevdSourceMap`, and bpd's own.
 *
 * Each is a [RequestType] the platform's DAP endpoint sends inside a session command, through
 * [DapSessionContext.endpoint] — the supported way to send a request `DapServer` does not declare.
 *
 * ## the bodies are JSON, not classes
 *
 * Arguments are built as a [JsonObject] by each arguments class's `toJson`, and answers are read as
 * one. No `@Serializable` class on either side, for the reason [ByMoved] has none: the answers are
 * bpd's serde output whole, and a class here would be a second copy of a vocabulary that has to
 * agree; the readers take them field by field instead ([obj] and its neighbours).
 *
 * The answer is declared `JsonElement?`, and both halves of that matter. The platform's endpoint
 * completes a request whose response has **no body** with null whatever the declared type says —
 * pydevd's `setPydevdSourceMap` and bpd's `bpd/understands` answer that way — so a non-null declared
 * type would be a promise the endpoint does not keep. And [JsonElement] rather than [JsonObject],
 * because a body that does not decode as the declared type is not a quiet null: the endpoint logs
 * it as an error and fails the request, and an answer from a newer bpd should cost the feature
 * that reads it, not put an error in front of the user. What shape the body is, is [send]'s
 * caller's question.
 */
internal object ByDapRequests {

    /**
     * Registers one `.by` file's mapping with pydevd.
     *
     * This is the whole trick behind source-mapped `.by` debugging under debugpy. The IDE cannot
     * translate a breakpoint on the way out — `DapBreakpointManager` builds requests from
     * `SourcePosition(VirtualFile, TextPosition)` with no hook to rewrite the path or the line — so
     * the translation happens in the debuggee instead. pydevd has first-class support for debugging
     * generated code (it is how notebook cell debugging works) and exposes it as this request: once
     * a map is registered for a `.by` file, breakpoints set against that file land on the
     * corresponding generated lines, and frames come back reported against the `.by` file.
     *
     * Sent by [BySourceMapPublisher]; pydevd answers with no body.
     */
    val setPydevdSourceMap: RequestType<JsonObject, JsonElement?> = custom("setPydevdSourceMap")

    /**
     * Which of bpd's own events this client reads.
     *
     * bpd narrates what it noticed on the console — the locals a jump bound to `None`, the
     * breakpoints the destination line will not fire for this pass — because for most clients that
     * is the only channel those facts have. It sends the same facts as data on `bpd/moved`, and a
     * client that reads both shows everything twice. Naming an event here turns its narration off.
     *
     * Sent once per session, beside the source maps; see [BySourceMapPublisher].
     */
    val understands: RequestType<JsonObject, JsonElement?> = custom("bpd/understands")

    /**
     * What `bpd` can prove about a frame's names, and how long each reading stays true.
     *
     * `bpd`'s alone: debugpy has no such request and answers `unknown command`, which is what the
     * data-flow feature reads as "this session has no facts" — see
     * [dev.basedpython.pycharm.debug.dfa.ByDataFlowRequests]. The answer is bpd's `Facts` whole,
     * which [dev.basedpython.pycharm.debug.dfa.ByDataFlowFacts] reads field by field.
     */
    val facts: RequestType<JsonObject, JsonElement?> = custom("bpd/facts")

    /**
     * Replace the code the running process holds for a set of files with the code that is on disk.
     *
     * `bpd`'s alone, and an extension for a reason DAP itself states: DAP's `restart` throws the
     * process away and starts another, and the whole point of this is that the process stays.
     *
     * A refusal is **not** an error response. bpd answers `success` and puts the whole account in
     * the body — a client given only "no" cannot show which of the user's edits to undo — so
     * nothing here throws for a replacement that could not be made. See
     * [dev.basedpython.pycharm.debug.hotswap.ByReplaced].
     */
    val replaceCode: RequestType<JsonObject, JsonElement?> = custom("bpd/replaceCode")

    /**
     * Why basedpython-ui scopes ran: the compose runtime's trace ring, read at a stop.
     *
     * `bpd`'s alone, and the program's rather than a frame's — any held thread answers, so the
     * body is empty and the request goes on the session's executor rather than a frame's. A
     * program with no compose runtime, or one whose tracing is off, is refused in a sentence (an
     * error response), which [refusalOf] reads back out and the tool window shows as the reason
     * there is nothing to show.
     */
    val recompositions: RequestType<JsonObject, JsonElement?> = custom("bpd/recompositions")

    /**
     * Start or stop the stream of `bpd/recomposition` events, which carry every trace record as it
     * is made while the program runs. Answers `{"watching": bool}` with what the flag now is.
     *
     * The events reach the observer [ByDebugAdapterDescriptor] registers; naming the event in
     * [understands] is what keeps bpd from narrating each run record on the console as well.
     */
    val watchRecompositions: RequestType<JsonObject, JsonElement?> = custom("bpd/watchRecompositions")

    private fun custom(command: String): RequestType<JsonObject, JsonElement?> =
        RequestType(command, JsonObject.serializer(), JsonElement.serializer().nullable)

    /**
     * The adapter's own sentence behind a failed request, or null when the failure is not the
     * adapter refusing.
     *
     * The platform's endpoint fails a request whose response says `success: false` with a
     * [DapRequestFailedException] carrying the error's formatted text, or the response's `message`
     * when there is none — so the sentence is its message, wherever in the causes it sits.
     */
    fun refusalOf(e: Throwable): String? =
        generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }
            .filterIsInstance<DapRequestFailedException>()
            .firstOrNull()
            ?.message
}

/**
 * Send one of [ByDapRequests] and wait for its answer, as an object when it is one.
 *
 * Null for an answer with no body and for one that is not a JSON object; a refused request throws
 * the endpoint's [DapRequestFailedException], which [ByDapRequests.refusalOf] reads.
 */
internal suspend fun DapSessionContext.send(
    request: RequestType<JsonObject, JsonElement?>,
    arguments: JsonObject,
): JsonObject? = endpoint.request(request, arguments).getOrThrow()?.objOrNull()

/** @see ByDapRequests.understands */
data class ByUnderstandsArguments(val events: List<String>) {
    fun toJson(): JsonObject = buildJsonObject {
        putJsonArray("events") { events.forEach { add(it) } }
    }
}

/**
 * pydevd reads [pydevdSourceMaps] entries as raw dictionaries (`source_map["line"]`,
 * `source_map["runtimeSource"]["path"]`), so these field names are the wire format and must match
 * exactly.
 */
data class SetPydevdSourceMapArguments(
    val source: DapSourceRef,
    val pydevdSourceMaps: List<PydevdSourceMap>,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("source", source.toJson())
        put("pydevdSourceMaps", buildJsonArray { pydevdSourceMaps.forEach { add(it.toJson()) } })
    }
}

data class DapSourceRef(val path: String) {
    fun toJson(): JsonObject = buildJsonObject { put("path", path) }
}

data class PydevdSourceMap(
    val line: Int,
    val endLine: Int,
    val runtimeSource: DapSourceRef,
    val runtimeLine: Int,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("line", line)
        put("endLine", endLine)
        putJsonObject("runtimeSource") { put("path", runtimeSource.path) }
        put("runtimeLine", runtimeLine)
    }
}

/** The request that registers one `.by` file's mapping. */
fun ByFileMapping.toRequest(): SetPydevdSourceMapArguments {
    val runtimeSource = DapSourceRef(generated)
    return SetPydevdSourceMapArguments(
        source = DapSourceRef(source),
        pydevdSourceMaps = runs.map {
            PydevdSourceMap(
                line = it.line,
                endLine = it.endLine,
                runtimeSource = runtimeSource,
                runtimeLine = it.runtimeLine,
            )
        },
    )
}
