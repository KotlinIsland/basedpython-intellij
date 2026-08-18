package dev.basedpython.pycharm.debug.dfa

import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import org.eclipse.lsp4j.services.LanguageServer
import java.util.concurrent.CompletableFuture

/**
 * The `by` language server, extended with the one request LSP does not have a shape for.
 *
 * Declared as an lsp4j protocol extension and returned from `LspServerDescriptor.lsp4jServerClass`,
 * which is the supported way to add a request the base protocol does not define — the same
 * mechanism [dev.basedpython.pycharm.debug.ByDebugProtocolServer] uses on the DAP side.
 *
 * Also carries [dev.basedpython.pycharm.lsp.ext.ByServerExtensions], the rest of `by`'s protocol
 * extensions, because a descriptor names one `lsp4jServerClass` and every custom request the server
 * answers has to be reachable through it.
 *
 * **Why not `inlayHint`.** An inlay hint request carries a document and a range and nothing else.
 * The answer here depends entirely on what a debugger saw, and there is nowhere in that request to
 * put it. **Why not `executeCommand`.** That is for things that have an effect; this is a question
 * with an answer.
 */
interface ByDataFlowServer : LanguageServer, dev.basedpython.pycharm.lsp.ext.ByServerExtensions {
    /**
     * What the program's own state settles about the code at and below the line it is stopped on.
     *
     * A `null` answer means the server declined — language services are off, or the document is
     * not one it serves. An **empty** answer is the ordinary case and means something different:
     * it looked and nothing was decidable. The two are not merged, because a client that treated
     * "did not look" as "nothing to draw" would silently stop drawing when the server was
     * misconfigured.
     */
    @JsonRequest("by/dataFlowAt")
    fun dataFlowAt(args: ByDataFlowParams): CompletableFuture<List<ByDataFlowFinding>?>
}

/**
 * Where the program is, and what it was holding there.
 *
 * Field names are the wire format and must match `ty_server`'s `DataFlowParams`, which is
 * `deny_unknown_fields` — a misspelling here is a refused request rather than a field quietly
 * ignored, which is the behaviour worth having.
 */
data class ByDataFlowParams(
    val textDocument: TextDocumentIdentifier,
    /** One-based, and the line the program is stopped on. */
    val line: Int,
    val observations: List<ByObservation>,
)

/**
 * One thing a debugger proved about one name.
 *
 * [observed] is the discriminator and the rest of the fields are whichever that kind carries; the
 * server reads them as an internally tagged enum. Built by [ByDataFlowFacts], which is where the
 * decision about *which* of a debugger's facts are worth sending lives.
 */
data class ByObservation(
    /** The name, or a dotted path such as `self.limit`. */
    val name: String,
    /** `isNone`, `isBool`, `isInt`, `isStr`, `isExactly` or `isEnumMember`. */
    val observed: String,
    val value: Boolean? = null,
    val text: String? = null,
    val module: String? = null,
    val qualname: String? = null,
    val member: String? = null,
)

/** One thing the state settles, ready to draw. */
data class ByDataFlowFinding(
    val range: Range,
    /** `condition`, `unreachable` or `value`. */
    val kind: String,
    /** Which way a condition goes; absent for anything else. */
    val taken: Boolean? = null,
    /**
     * What a decided read will find — `0.0`, `3`, `False`; absent for anything else.
     *
     * Nothing here draws it: [label] already spells it as `discount = 0.0`, which is what goes in
     * the margin. It is carried because the server sends it, and a client that wanted to do
     * anything but draw the label — colour by value, offer it for a copy — should not have to take
     * a string written for a reader back apart.
     */
    val value: String? = null,
    /** What to draw beside the source. */
    val label: String,
)
