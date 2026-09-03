package dev.basedpython.pycharm.lsp.ext

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
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

    /**
     * What one file's slot in a running build's tree should now contain.
     *
     * Asked of the server rather than of `by` on the command line, and the reason is measured: a
     * full build of a 97-file project takes 24.9 seconds, of which `by check` is 8.5. A subprocess
     * would pay project discovery and that whole check on every press of a button. The server has
     * already paid both — it holds the project database, warm — so what is left is one file's emit.
     *
     * It **writes nothing**. The answer is the bytes and where they go, and the caller writes them,
     * because the caller is the only one that can undo that write together with the debugger
     * request that follows it.
     *
     * A `null` answer means the server declined — language services are off, or the document has no
     * file behind it. A response carrying [ByRestaged.refused] means it looked and would not: a tree
     * built by a different `by`, a `--compiled` build whose modules are native extensions with no
     * `__code__` to assign, or a file that does not check.
     */
    @JsonRequest("by/transpileForBuild")
    fun transpileForBuild(args: ByTranspileForBuildParams): CompletableFuture<ByRestaged?>

    /**
     * Which assignments the author lined up, so that drawing inlay hints does not take the column
     * apart — see [dev.basedpython.pycharm.lsp.inlay.ByAlignment].
     *
     * **Why not `textDocument/inlayHint`.** That request can only answer *about hints*, and the
     * lines that matter most here are the ones with no hint at all: in
     *
     * ```
     * a     = [1, 2]
     * basdf = 1
     * ```
     *
     * it is `basdf` that has to move, and `basdf` gets no hint, because `by` suppresses the type of
     * a bare literal. There is nowhere in an inlay hint reply to hang a line that has no hint.
     *
     * **Why the server at all.** Whether a run of assignments is a block the author aligned is a
     * question about the source — which statements are siblings, where a suite ends, what is an
     * assignment and what merely looks like one — and the server is holding the parse. Recovering
     * that from the document text means a regex, and a regex cannot tell an `=` in code from one in
     * a string. What the server deliberately does *not* decide is how wide anything ends up: only
     * the client knows which hints are on screen this instant.
     *
     * A `null` answer means the server declined — language services or every hint kind are off.
     */
    @JsonRequest("by/alignmentGroups")
    fun alignmentGroups(args: ByAlignmentGroupsParams): CompletableFuture<List<ByAlignmentGroup>?>
}

/**
 * The document to look through, and how much of it.
 *
 * Field names are the wire format and must match `ty_server`'s `AlignmentGroupsParams`, which is
 * `deny_unknown_fields`. [range] mirrors the one sent to `textDocument/inlayHint` so that both
 * questions are asked about the same span; a group that only partly overlaps it comes back whole,
 * since a column is a property of every member at once.
 */
data class ByAlignmentGroupsParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
)

/** Assignments sharing one `=` column, which therefore have to be laid out together. */
data class ByAlignmentGroup(val members: List<ByAlignmentMember> = emptyList())

/** One assignment's contribution to the column. */
data class ByAlignmentMember(
    /**
     * The end of the target: where the padding starts, and where a variable's type hint for this
     * line is positioned. Hints are matched to members by this position.
     */
    val gapStart: Position,
    /** The `=`. */
    val gapEnd: Position,
)

/**
 * Which file was edited, and which tree is running.
 *
 * Field names are the wire format and must match `ty_server`'s `TranspileForBuildParams`, which is
 * `deny_unknown_fields`.
 */
data class ByTranspileForBuildParams(
    val textDocument: TextDocumentIdentifier,
    /**
     * The build tree the program is running out of.
     *
     * The IDE knows this and the server cannot: `by run` chooses a temp directory, and the only
     * thing that sees the name is the process that started the program.
     */
    val buildDirectory: String,
)

/**
 * One file's slot in the tree, or why it will not be recomputed.
 *
 * One type for both answers because the server sends one untagged shape: [refused] is set on a
 * refusal and [generated] on a success, and exactly one of them is.
 */
data class ByRestaged(
    /** Where the bytes go: absolute, inside the build directory. */
    val generated: String? = null,
    /** The full text to write there. */
    val content: String? = null,
    /**
     * The full new text of `_by_sourcemap.py`, or null when nothing about the map changed.
     *
     * Null for every file the build copied rather than transpiled — a hand-written `.py` has no
     * entry in the map, because nothing generated it.
     */
    val sourcemap: String? = null,
    /** sha-256 of the source this was produced from. */
    val byDigest: String? = null,
    /** sha-256 of [content]. */
    val pyDigest: String? = null,
    /**
     * Whether these bytes differ from what the tree already holds.
     *
     * False is the file already being what the process is running, which is a different fact from
     * nothing being replaceable and must not be shown as one.
     */
    val changed: Boolean = false,
    /** Why it will not be recomputed, written for a user. Null when it was. */
    val refused: String? = null,
    /** What the checker said, when that is why. */
    val diagnostics: List<String> = emptyList(),
)

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
