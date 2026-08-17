package dev.basedpython.pycharm.debug

import org.eclipse.lsp4j.debug.services.IDebugProtocolServer
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

/**
 * The DAP server interface extended with pydevd's `setPydevdSourceMap` request.
 *
 * This is the whole trick behind source-mapped `.by` debugging. The IDE cannot translate a
 * breakpoint on the way out — `DapBreakpointManager` builds requests from
 * `SourcePosition(VirtualFile, TextPosition)` with no hook to rewrite the path or the line — so the
 * translation happens in the debuggee instead. pydevd has first-class support for debugging
 * generated code (it is how notebook cell debugging works) and exposes it as this custom request:
 * once a map is registered for a `.by` file, breakpoints set against that file land on the
 * corresponding generated lines, and frames come back reported against the `.by` file.
 *
 * Declared as an lsp4j protocol extension and returned from
 * `DebugAdapterDescriptor.debugAdapterServerClass`, which is the supported way to add a request the
 * base protocol does not have.
 */
interface ByDebugProtocolServer : IDebugProtocolServer {
    /**
     * Result type is deliberately untyped: pydevd answers with an empty body, and a
     * `CompletableFuture<Void>` would ask Gson to materialise `{}` as `Void`.
     */
    @JsonRequest("setPydevdSourceMap")
    fun setPydevdSourceMap(args: SetPydevdSourceMapArguments): CompletableFuture<Any?>

    /**
     * What `bpd` can prove about a frame's names, and how long each reading stays true.
     *
     * `bpd`'s alone: debugpy has no such request and answers `unknown command`, which is what the
     * data-flow feature reads as "this session has no facts" — see
     * [dev.basedpython.pycharm.debug.dfa.ByDataFlowRequests].
     *
     * Untyped for the reason [setPydevdSourceMap]'s result is: the answer is a shape
     * [dev.basedpython.pycharm.debug.dfa.ByDataFlowFacts] reads field by field, and giving Gson a
     * class for it would put the same vocabulary in two places that have to agree.
     */
    @JsonRequest("bpd/facts")
    fun facts(args: dev.basedpython.pycharm.debug.dfa.ByFactsArguments): CompletableFuture<Any?>

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
    @JsonRequest("bpd/understands")
    fun understands(args: ByUnderstandsArguments): CompletableFuture<Any?>
}

/** @see ByDebugProtocolServer.understands */
data class ByUnderstandsArguments(val events: List<String>)

/**
 * pydevd reads [pydevdSourceMaps] entries as raw dictionaries (`source_map["line"]`,
 * `source_map["runtimeSource"]["path"]`), so these field names are the wire format and must match
 * exactly.
 */
data class SetPydevdSourceMapArguments(
    val source: DapSourceRef,
    val pydevdSourceMaps: List<PydevdSourceMap>,
)

data class DapSourceRef(val path: String)

data class PydevdSourceMap(
    val line: Int,
    val endLine: Int,
    val runtimeSource: DapSourceRef,
    val runtimeLine: Int,
)

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
