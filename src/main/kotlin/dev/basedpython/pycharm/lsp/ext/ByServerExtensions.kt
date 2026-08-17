package dev.basedpython.pycharm.lsp.ext

import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

// ---------------------------------------------------------------------------
// `by`
// ---------------------------------------------------------------------------

/**
 * The requests `by` answers that LSP has no shape for.
 *
 * Declared as lsp4j protocol extensions and reached through
 * `LspServerDescriptor.lsp4jServerClass`, which is the supported way to add a request the base
 * protocol does not define.
 *
 * Each of these was a subprocess once, and that is what they have in common. A subprocess reads the
 * file, and an editor's copy of a file is the buffer — so it answered about the last save rather
 * than about what is on screen. It also resolves the project's configuration by a route the editor
 * does not use, and can disagree with the diagnostics in the same window about what the file means.
 */
interface ByServerExtensions {

    /**
     * The python a document lowers to, or the basedpython a python file reverses into.
     *
     * A `null` answer means the server declined — the document is not one it serves. A response
     * carrying [ByTranspileResponse.error] means it looked and the source does not lower yet, which
     * is an ordinary state for a file being edited rather than a failure of the request.
     */
    @JsonRequest("by/transpile")
    fun transpile(args: ByTranspileParams): CompletableFuture<ByTranspileResponse?>

    /** What one of the type checker's rules means, or `null` if this server does not own it. */
    @JsonRequest("by/explainRule")
    fun explainRule(args: ByExplainRuleParams): CompletableFuture<ByRuleExplanation?>

    /**
     * Every basedpython-specific construct the document uses, and what each lowers to.
     *
     * Answered off the parse tree the transpiler itself runs on, which is why it is asked of the
     * server rather than worked out here: recognising these from the source text means a regex per
     * construct, and a regex cannot tell an operator from the same characters in a string or a
     * comment, nor keep up with a language that grows one.
     */
    @JsonRequest("by/explainTranspilation")
    fun explainTranspilation(
        args: ByExplainTranspilationParams,
    ): CompletableFuture<List<ByTranspilationNote>?>
}

/** The document to look through. */
data class ByExplainTranspilationParams(val textDocument: TextDocumentIdentifier)

/** One construct found, and what the transpiler does with it. */
data class ByTranspilationNote(
    /** A short, stable name, e.g. `null-safe access`. */
    val construct: String? = null,
    /** The source it was written as. */
    val snippet: String? = null,
    /** What it lowers to, in a sentence. */
    val explanation: String? = null,
    /** The one-based line it is on. */
    val line: Int = 0,
)

/**
 * Which document, and which way.
 *
 * Field names are the wire format and must match `ty_server`'s `TranspileParams`, which is
 * `deny_unknown_fields` — a misspelling here is a refused request rather than a field quietly
 * ignored, which is the behaviour worth having.
 */
data class ByTranspileParams(
    val textDocument: TextDocumentIdentifier,
    /** When true, go the other way: python in, basedpython out. */
    val reverse: Boolean = false,
    /**
     * Text to transpile instead of the document's own, for a fragment that is not a file.
     *
     * A selection has no document of its own, and [textDocument] only says which one it came from
     * — the fragment is transpiled on its own, which is all a fragment can be.
     */
    val source: String? = null,
)

/** What came out, or why nothing did. Exactly one of the two is set. */
data class ByTranspileResponse(
    val source: String? = null,
    val error: String? = null,
)

/** The rule to look up, by the name a diagnostic reports it under. */
data class ByExplainRuleParams(val name: String)

// ---------------------------------------------------------------------------
// `buff`
// ---------------------------------------------------------------------------

/** The one request `buff` answers that LSP has no shape for. */
interface BuffServerExtensions {

    /** What one of the linter's rules means, or `null` if this server does not own it. */
    @JsonRequest("buff/explainRule")
    fun explainRule(args: BuffExplainRuleParams): CompletableFuture<ByRuleExplanation?>
}

/** The rule to look up, by either of the two names it has: a code (`F401`) or a name. */
data class BuffExplainRuleParams(val code: String)

/**
 * What a rule is, ready to show.
 *
 * One type for both servers, because the two answer the same question about disjoint sets of rules,
 * and a caller that had to care which one replied would be carrying that split for no reason. The
 * linter fills in [code]; the type checker's rules have no code and fill in [summary] instead.
 */
data class ByRuleExplanation(
    val name: String? = null,
    val code: String? = null,
    val summary: String? = null,
    /** The full explanation, in markdown. */
    val documentation: String? = null,
)

/**
 * The `buff` language server, extended with the request above.
 *
 * A named interface because `LspServerDescriptor.lsp4jServerClass` takes a class, and it has to be
 * one that is both a [org.eclipse.lsp4j.services.LanguageServer] and carries the extension.
 */
interface BuffLanguageServer : org.eclipse.lsp4j.services.LanguageServer, BuffServerExtensions
