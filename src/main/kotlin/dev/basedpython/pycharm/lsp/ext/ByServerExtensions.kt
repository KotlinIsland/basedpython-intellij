package dev.basedpython.pycharm.lsp.ext

import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.FoldingRangeRequestParams
import org.eclipse.lsp4j.FormattingOptions
import org.eclipse.lsp4j.ImplementationParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.WorkspaceDiagnosticParams
import org.eclipse.lsp4j.WorkspaceDiagnosticReport
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest
import java.util.concurrent.CompletableFuture

// ---------------------------------------------------------------------------
// `by`
// ---------------------------------------------------------------------------

/**
 * The requests `by` answers that LSP has no shape for.
 *
 * Declared as lsp4j protocol extensions and reached through
 * `LspClientDescriptor.lsp4jServerClass`, which is the supported way to add a request the base
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
     * What every edited file's slot in a running build's tree should now contain, and the one
     * `_by_sourcemap.py` describing them together.
     *
     * Asked of the server rather than of `by` on the command line, and the reason is measured: a
     * full build of a 97-file project takes 24.9 seconds, of which `by check` is 8.5. A subprocess
     * would pay project discovery and that whole check on every press of a button. The server has
     * already paid both — it holds the project database, warm — so what is left is the edit's emit.
     *
     * It **writes nothing**. The answer is the bytes and where they go, and the caller writes them,
     * because the caller is the only one that can undo that write together with the debugger
     * request that follows it.
     *
     * A response carrying [ByRestage.refusals] means it looked and would not: a tree built by a
     * different `by`, a `--compiled` build whose modules are native extensions with no `__code__` to
     * assign, a document with no file behind it, or a file that does not check.
     */
    @JsonRequest("by/transpileForBuild")
    fun transpileForBuild(args: ByTranspileForBuildParams): CompletableFuture<ByRestage?>

    /**
     * Which assignments share an `=` column, so that drawing inlay hints does not take the column
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

    /**
     * Where this document holds a fragment of another language, and which language each is in —
     * see [dev.basedpython.pycharm.lsp.inject.BasedPythonLanguageInjector].
     *
     * **Why the server.** Which language a string is written in is a question about the program,
     * not about the text: the marker may be a comment on the statement above it, or the annotation
     * on a parameter in another module, reached through a function that only hands the value on.
     * The first of those an editor could find for itself; the other two need the resolved project
     * the diagnostics in the same window came from, and re-deriving them here would mean the plugin
     * carrying its own half-answer to a question `by` answers exactly.
     *
     * **Why not `textDocument/semanticTokens`.** A semantic token colours a span with one of a
     * fixed set of kinds. This has to name a language the server has never heard of and that only
     * the editor can act on.
     *
     * A `null` answer means the server declined — language services are off, or the document is not
     * one it serves. An **empty** list is the ordinary answer for a file with no fragments in it.
     */
    @JsonRequest("by/injections")
    fun injections(args: ByInjectionsParams): CompletableFuture<ByInjectionsResponse?>

    /**
     * Where a path stands in its project's build: the directory `by build` writes, the file a
     * source is written to, the source a generated file came from — see
     * [dev.basedpython.pycharm.lsp.build.ByBuildOutputs].
     *
     * **Why the server.** The layout is the build's, and it is not the one an editor would guess:
     * the directory is `build/`, and the path inside it follows the *module* tree — a src-layout
     * project's `src/pkg/main.by` is written to `build/pkg/main.py`. The server holds the same
     * project database, module roots and file set the build reads, so its answer is the build's.
     *
     * Not a document request: a directory, or a generated file the editor has never opened, is
     * asked about as often as a source.
     *
     * A `null` answer means no project in the session holds the path.
     */
    @JsonRequest("by/buildOutput")
    fun buildOutput(args: ByBuildOutputParams): CompletableFuture<ByBuildOutput?>

    /**
     * How a module runs as a program: its top-level `main` as the transpiler reads it, and its
     * hand-written `__main__` guards — see [dev.basedpython.pycharm.run.model.ByProgramModel].
     *
     * **Why the server.** Which `main` counts, whether the module already calls it, which parameters
     * the command line fills and how each is spelled are decisions of the transpiler pass that
     * generates the entry point and its argument parser. The answer is that pass's own reading, so it
     * cannot drift from what `by run` then does — as a regex over the source did, on a generic
     * `def main[T](…)` and on a `main(` at the start of a docstring line.
     *
     * Asked by file URI rather than by open document: a run configuration names a module, and the
     * file behind it is usually not open. A `null` answer means no project holds the file.
     */
    @JsonRequest("by/entryPoint")
    fun entryPoint(args: ByEntryPointParams): CompletableFuture<ByEntryPointResponse?>

    /**
     * The tests pytest would collect, from the checker's static model of pytest's collection
     * rules — for one file, or for every file of every project.
     *
     * **Why not `pytest --collect-only`.** Collecting imports every test module, which runs its
     * top-level code; an editor drawing a gutter icon must not execute user code on the user's
     * behalf. **Why not `textDocument/documentSymbol`.** Whether a declaration is collected depends
     * on nesting, inheritance, constructors and fixtures — not on anything a symbol outline carries.
     */
    @JsonRequest("by/testItems")
    fun testItems(args: ByTestItemsParams): CompletableFuture<ByTestItemsResponse?>

    /**
     * The module names `by run` runs each project file under, the project's configured `run.main`,
     * and optionally one name resolved to its file — all as `by run` itself resolves them.
     */
    @JsonRequest("by/runModules")
    fun runModules(args: ByRunModulesParams): CompletableFuture<ByRunModulesResponse?>

    /**
     * A document's block structure and string literals, as the parser reads them — see
     * [dev.basedpython.pycharm.lsp.outline.ByOutlines].
     *
     * **Why the server.** Which line opens a suite, which `else` belongs to which statement, where
     * a string's escapes are and how much indentation basedpython strips from it are all decided
     * by the parser and the transpiler. Every scanner this plugin carried to answer them from the
     * text answered some of them differently: `match: int = 1` read as a match statement, a `"`
     * toggled with no regard for escapes, a margin drawn on a docstring nothing is stripped from.
     *
     * **Why not the standard requests.** `foldingRange` and `selectionRange` give ranges without
     * saying which is a header or a suite, and semantic tokens cannot overlap, which an
     * interpolation's tokens inside its braces do. The keyword pairs a caret lights up *are* a
     * standard request, `textDocument/documentHighlight`, and are asked for there.
     *
     * A `null` answer means the server declined — language services are off, or the document is a
     * notebook or a template.
     */
    @JsonRequest("by/syntaxOutline")
    fun syntaxOutline(args: BySyntaxOutlineParams): CompletableFuture<BySyntaxOutlineResponse?>

    /**
     * The superclass members a class member overrides — see
     * [dev.basedpython.pycharm.lsp.supers.BySupers].
     *
     * **Why the server.** What a member overrides is the override checks' answer: which bases count,
     * which declarations along the MRO a member is compared with, what a `private` member or a
     * synthesized one does. `by` answers it from the checks' own walk, so Go to Super goes to exactly
     * what `invalid-method-override` holds the member to.
     *
     * **Why not the standard requests.** `textDocument/implementation` goes the other way, from a
     * member to the members that override it, and `typeHierarchy/supertypes` answers about classes,
     * not their members.
     *
     * A `null` answer means no class member is declared at the position; an **empty** list is a
     * member that overrides nothing. A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/superMembers")
    fun superMembers(args: BySuperMembersParams): CompletableFuture<List<BySuperMember>?>

    /**
     * Every class member of a document that overrides something, each with what `by/superMembers`
     * answers for it — see [dev.basedpython.pycharm.lsp.supers.ByOverridingMarkers], which draws
     * the gutter's *overrides* and *implements* icons from it.
     *
     * **Why one request for the document.** The icons are drawn on every highlighting pass, so
     * `by/superMembers` per member would be a request per member per pass, most of them answered
     * with an empty list. `by` answers this from the same code in one pass over the document.
     *
     * A `null` answer means the server declined — language services are off, or the document is not
     * one it answers for. An **empty** list is a document none of whose members overrides anything.
     * A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/documentSuperMembers")
    fun documentSuperMembers(args: ByDocumentSuperMembersParams): CompletableFuture<List<ByOverridingMember>?>

    /**
     * The methods the class at a position inherits and could override, the abstract ones — those it
     * has to implement — marked, each with the declaration `by/overrideMembers` writes for it — see
     * [dev.basedpython.pycharm.lsp.supers.ByOverrideMembers], behind Override Methods (Ctrl+O) and
     * Implement Methods (Ctrl+I).
     *
     * **Why not a code action.** A code action's edit is worked out before anything is picked, and
     * here the edit is what the user picks from this list.
     *
     * A `null` answer means there is no class at the position, or the server declined. A `by` from
     * before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/overridableMembers")
    fun overridableMembers(args: ByOverridableMembersParams): CompletableFuture<ByOverridableMembers?>

    /**
     * The declarations overriding the members picked from `by/overridableMembers`, written the way
     * basedpython writes them, as edits — with the imports they need — and the first one's body,
     * where the caret goes. `null` when none of them is a member the class could override.
     */
    @JsonRequest("by/overrideMembers")
    fun overrideMembers(args: ByOverrideMembersParams): CompletableFuture<ByOverrideMembers?>

    /**
     * The expression a debugger evaluates for a position: on `d` in `a.b[c].d`, all of `a.b[c].d` —
     * see [dev.basedpython.pycharm.debug.ByDapEvaluator], which a stopped session's value tooltip,
     * *Evaluate Expression* and *Quick Evaluate* start from.
     *
     * A `null` answer means there is nothing to evaluate there: a literal, an operator, a keyword, a
     * comment, or a name a `def`, a class, an import or a keyword argument declares. A `by` from
     * before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/evaluatableExpression")
    fun evaluatableExpression(args: ByEvaluatableExpressionParams): CompletableFuture<ByEvaluatableExpression?>

    /**
     * Each declaration of a document — its classes and functions, and the methods and properties of
     * those classes — with how many places use it and how many classes subclass it or members
     * override it: the code vision above each one, drawn by
     * [dev.basedpython.pycharm.lsp.vision.ByUsagesCodeVisionProvider] and
     * [dev.basedpython.pycharm.lsp.vision.ByInheritorsCodeVisionProvider].
     *
     * **Why not `textDocument/codeLens`.** The platform asks for a document's lenses again only when
     * that document changes, so a count would stay as it was while another file gained or lost a
     * usage; and it draws every lens under the one *LSP Code Lens* setting. These are drawn on every
     * highlighting pass instead, under the *Usages* and *Inheritors* settings Java, Kotlin and Python
     * share. Each count is what `textDocument/references` or `textDocument/implementation` answers at
     * the declaration, from the same code, which is what a click on it asks.
     *
     * Only the [ByDocumentUsageCountsParams.counts] named are worked out: each is a search of the
     * workspace per declaration. A `null` answer means the server declined — language services are
     * off, or the document is not one it answers for. A `by` from before the request answers
     * `MethodNotFound`.
     */
    @JsonRequest("by/documentUsageCounts")
    fun documentUsageCounts(args: ByDocumentUsageCountsParams): CompletableFuture<List<ByDeclarationUsages>?>

    /**
     * Every file of the workspace checked, answered as soon as the check is done.
     *
     * The same parameters and report as `workspace/diagnostic`, which `by` long-polls: a request
     * whose answer the client already has — no file changed, or no file has anything wrong — is
     * held open until that stops being so. That keeps a problems list current, and it is what
     * [dev.basedpython.pycharm.lsp.diagnostics.ByProjectDiagnostics] uses. An inspection run asks
     * once and needs the answer, including "nothing", so it asks this instead.
     *
     * Refused with an error, rather than answered empty, when the server is not checking the
     * workspace at all.
     */
    @JsonRequest("by/checkWorkspace")
    fun checkWorkspace(params: WorkspaceDiagnosticParams): CompletableFuture<WorkspaceDiagnosticReport?>

    /**
     * The dotted name a file or a directory is imported by — see
     * [dev.basedpython.pycharm.navigation.ByQualifiedNameProvider].
     *
     * **Why the server.** The name is the module resolver's: which search path the file is under, a
     * src layout's `src/` included, and whether resolution reaches the file at all. A name worked out
     * here from the path relative to some root agrees with it only until a project has two roots.
     *
     * A `null` answer is a path nothing is imported from. A `by` from before the request answers
     * `MethodNotFound`.
     */
    @JsonRequest("by/moduleName")
    fun moduleName(args: ByModuleNameParams): CompletableFuture<String?>

    /**
     * What the name at a position declares or refers to: its kind, its name, its dotted name, and
     * where it is declared — for Copy Reference and for naming what a usage search searched for.
     *
     * **Why the server.** Which declaration a use refers to is resolution, and a dotted name is the
     * module resolver's name for the file plus the classes the declaration is made in. A `.by` file's
     * PSI has neither.
     *
     * A `null` answer is a position on no name, or on a name whose definitions declare different
     * things; `qualifiedName` is `null` for a name local to a function. A `by` from before the
     * request answers `MethodNotFound`.
     */
    @JsonRequest("by/declaration")
    fun declaration(args: ByDeclarationParams): CompletableFuture<ByDeclaration?>

    /**
     * `textDocument/references` with what each reference does with the name: the same references in
     * the same order, each with its [ByReference.kind]. `null` where `textDocument/references` is. A
     * `by` from before the request answers `MethodNotFound`.
     */
    /**
     * Every class the class whose name is at the position inherits from, in its method resolution
     * order without itself, as `typeHierarchy/supertypes` items. `null` where there is no class. A
     * `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/mro")
    fun mro(args: ByMroParams): CompletableFuture<List<TypeHierarchyItem>?>

    /**
     * `textDocument/completion` kept to the items that fit the type the position expects, or `null`
     * where it expects none. A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/smartCompletion")
    fun smartCompletion(params: org.eclipse.lsp4j.CompletionParams): CompletableFuture<org.eclipse.lsp4j.CompletionList?>

    /**
     * Every rule `by` reports diagnostics under, by name, with its summary, default level and status.
     * A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/rules")
    fun rules(): CompletableFuture<List<ByRule>?>

    /**
     * Every HTTP route the session's projects serve, FastAPI's and django's, ordered by path and
     * method. Takes `{}`. A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/endpoints")
    fun endpoints(params: Map<String, Any>): CompletableFuture<List<ByEndpoint>?>

    @JsonRequest("by/references")
    fun references(args: ReferenceParams): CompletableFuture<List<ByReference>?>

    /**
     * Every class of the document, in source order, with the members it inherits: what each class of
     * its MRO declares, from the first to declare the name, none it declares itself, `object`'s last.
     * Depends on every file the classes inherit from. A `by` from before the request answers
     * `MethodNotFound`.
     */
    @JsonRequest("by/documentInheritedMembers")
    fun documentInheritedMembers(args: BySyntaxOutlineParams): CompletableFuture<List<ByInheritingClass>?>

    /**
     * Every expression of the statement that contains a range, innermost first, each with its type
     * as the document's language spells it — Type Info's chooser, and Surround With's test for an
     * expression.
     *
     * An empty answer is a range in no expression; `null` is language services being off. A `by`
     * from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/expressionTypes")
    fun expressionTypes(args: ByExpressionTypesParams): CompletableFuture<List<ByExpressionType>?>

    /**
     * The code a test, a class of tests or a test module exercises: the project's own functions,
     * methods and classes it calls — see [dev.basedpython.pycharm.navigation.tests.ByTestFinder].
     *
     * **Why the server.** A test exercises what it calls, which is call hierarchy's answer, and which
     * declarations are tests is pytest's collection as `by/testItems` models it. Neither is a file
     * name: `test_circle.by` tests whatever it calls, wherever that is.
     *
     * A `null` answer means no test is collected from the document. A `by` from before the request
     * answers `MethodNotFound`.
     */
    @JsonRequest("by/testSubjects")
    fun testSubjects(args: ByTestLinksParams): CompletableFuture<List<ByTestLink>?>

    /**
     * The collected tests that call a function, a method, a class or anything a module declares,
     * and where a new test of it goes — the other direction of [testSubjects].
     *
     * A `null` answer means the document is not one of the project's. A `by` from before the request
     * answers `MethodNotFound`.
     */
    @JsonRequest("by/subjectTests")
    fun subjectTests(args: ByTestLinksParams): CompletableFuture<BySubjectTests?>

    /**
     * The statement at a position completed, and the line after it started — Complete Current
     * Statement; see [dev.basedpython.pycharm.editor.smart.BySmartEnterProcessor].
     *
     * **Why the server.** What is missing is the parser's to say: which brackets are open (not the
     * ones in a string or a comment), whether the line is a clause header (`match = 1` is not), where
     * a statement spread over several lines ends, and which statement an `else` continues.
     *
     * **Why not the standard requests.** The answer is edits *and* where the caret goes once they are
     * made, a place in the edited text; a code action's workspace edit has no caret, and
     * `textDocument/onTypeFormatting` answers for a character typed, not a statement to finish.
     *
     * A `null` answer means the server declined — language services are off, or the document is a
     * notebook or a template. A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/completeStatement")
    fun completeStatement(args: ByCompleteStatementParams): CompletableFuture<ByCompletedStatement?>

    /**
     * What Code | Unwrap/Remove offers around a range: each statement around it that unwraps into
     * one of its suites, and each clause that can be removed, innermost first — see
     * [dev.basedpython.pycharm.editor.unwrap.ByUnwrapDescriptor].
     *
     * **Why the server.** Which suite a caret is in, which `else` is whose, and which lines of a
     * suite are its indentation rather than the inside of a string literal are the parser's to say;
     * whether a `break` would leave a different loop once its loop is gone, and whether a `try`
     * can do without its last `except`, are the language's.
     *
     * **Why not `textDocument/codeAction`.** The platform lists the options in a popup and, while
     * one is selected, marks what it deletes apart from what it keeps. A code action carries no
     * ranges to draw that with.
     *
     * A `null` answer means the server declined — language services are off, or the document is a
     * notebook or a template. A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/unwrap")
    fun unwrap(params: ByUnwrapParams): CompletableFuture<ByUnwrapResponse?>

    /**
     * The docstring to fill an empty one at the top of a function's body with: a summary line for
     * the caret, the parameters but the receiver, and what the function returns or yields — see
     * [dev.basedpython.pycharm.editor.smart.ByDocstringStubs], which asks it once Enter has opened
     * an empty `"""` docstring.
     *
     * **Why the server.** Which parameters a function has, which of them is the receiver, whether
     * it is a generator and what its annotation says it returns are the parser's and the checker's;
     * the format is the file's own other docstrings', which is `by`'s to read.
     *
     * A `null` answer means there is no empty function docstring at the position, or the server
     * declined. A `by` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("by/docstringStub")
    fun docstringStub(params: ByDocstringStubParams): CompletableFuture<ByDocstringStub?>

    /**
     * The imports some code copied out of a document depends on: each name the copied text uses that
     * the document's global scope binds where the text was copied from, with the import that binds it
     * again in another module — see [dev.basedpython.pycharm.lsp.imports.ByPasteImports], which asks
     * it once the text has been pasted.
     *
     * **Why the text, not a range.** A cut takes the text out of the document, and what its names
     * meant is what the scope at the place it was cut from binds, which the cut leaves where it was.
     * So the request carries the copied text and that place, and is answered about the document as
     * it is when asked (`textHash`); the answer describes each import — a module and a member —
     * rather than pointing into the document.
     *
     * A `null` answer means the server declined — language services are off, or the document is a
     * notebook or a template. An **empty** list is code that needs no import. A `by` from before the
     * request answers `MethodNotFound`.
     */
    @JsonRequest("by/copiedImports")
    fun copiedImports(params: ByCopiedImportsParams): CompletableFuture<List<ByCopiedImport>?>

    /**
     * Which of the imports `by/copiedImports` answered the code pasted at a range is missing, and the
     * edits that add all of them.
     *
     * An import is missing when its name is not bound in the scope the paste landed in. The edits of
     * a subset of the imports are not a subset of the edits — two imports from one module can go into
     * one statement — so a choice among them is asked again with only the ones chosen.
     *
     * A `null` answer means the server declined: language services are off, the document is a
     * notebook or a template, or the range is not in it. A `by` from before the request answers
     * `MethodNotFound`.
     */
    @JsonRequest("by/pastedImports")
    fun pastedImports(params: ByPastedImportsParams): CompletableFuture<ByPastedImports?>

    /**
     * The top-level function, class or variable that F6 at a position would move: in its name or in
     * a reference to it — see [dev.basedpython.pycharm.lsp.refactor.ByMoveSymbolHandler].
     *
     * **Why not a code action.** The user picks where it goes, and a code action's edit is worked
     * out before anything is picked.
     *
     * A `null` answer is a position with nothing to move. A `by` from before the request answers
     * `MethodNotFound`.
     */
    @JsonRequest("by/prepareMoveSymbol")
    fun prepareMoveSymbol(params: ByPositionParams): CompletableFuture<ByMovableSymbol?>

    /**
     * The edits that move what [prepareMoveSymbol] named into the module [ByMoveSymbolParams.target],
     * an existing file, with every reference in the project following it — or why it is not moved.
     */
    @JsonRequest("by/moveSymbol")
    fun moveSymbol(params: ByMoveSymbolParams): CompletableFuture<ByProjectEditAnswer?>

    /**
     * The class at a position, the members it could move, and the project's classes above and below
     * it — Pull Members Up and Push Members Down; see
     * [dev.basedpython.pycharm.lsp.refactor.ByMoveMembers]. `null` outside a class.
     */
    @JsonRequest("by/prepareMoveMembers")
    fun prepareMoveMembers(params: ByPositionParams): CompletableFuture<ByMovableMembers?>

    /** The edits that move members of the class at a position into one of its superclasses, or why not. */
    @JsonRequest("by/pullMembersUp")
    fun pullMembersUp(params: ByPullMembersUpParams): CompletableFuture<ByProjectEditAnswer?>

    /** The edits that move members of the class at a position into its subclasses, or why not. */
    @JsonRequest("by/pushMembersDown")
    fun pushMembersDown(params: ByPushMembersDownParams): CompletableFuture<ByProjectEditAnswer?>

    /**
     * The edits that make a new class above the class at a position, with the members named, as its
     * one base — Extract Superclass — or why not.
     */
    @JsonRequest("by/extractSuperclass")
    fun extractSuperclass(params: ByExtractSuperclassParams): CompletableFuture<ByProjectEditAnswer?>

    /**
     * The function at a position — in its name or parameters, or in a name referring to it — and its
     * parameters as the source writes them, without a method's `self` or `cls`: Change Signature's
     * starting point, see [dev.basedpython.pycharm.lsp.refactor.ByChangeSignature]. `null` where
     * there is no function.
     */
    @JsonRequest("by/prepareChangeSignature")
    fun prepareChangeSignature(params: ByPositionParams): CompletableFuture<BySignature?>

    /**
     * The edits that give the function at a position the parameter list named, every call in the
     * project rebound to it as python binds it — or why not.
     */
    @JsonRequest("by/changeSignature")
    fun changeSignature(params: ByChangeSignatureParams): CompletableFuture<ByProjectEditAnswer?>
}

/** The file or directory to name. */
data class ByModuleNameParams(val uri: String)

/** The name to ask about: where it is declared or anywhere it is used. */
data class ByDeclarationParams(val textDocument: TextDocumentIdentifier, val position: Position)

/** What a name declares or refers to, and where, from `by/declaration`. */
data class ByDeclaration(
    /** One of the kinds a hover names: `class`, `method`, `function`, `property`, `variable`, … */
    val kind: String? = null,
    val name: String? = null,
    /** `shapes.base.Circle.area`; `null` for a name local to a function or a file nothing imports. */
    val qualifiedName: String? = null,
    /** The name at the position asked about. */
    val originSelectionRange: Range? = null,
    val uri: String? = null,
    /** The whole declaration. */
    val range: Range? = null,
    /** The declaration's name. */
    val selectionRange: Range? = null,
)

/** A selection, or an empty range at the caret. */
data class ByExpressionTypesParams(val textDocument: TextDocumentIdentifier, val range: Range)

/** One expression around a range and its type. */
data class ByExpressionType(val range: Range? = null, val type: String? = null)

/**
 * Where to start from: a test, a class of tests or anywhere in a test module for `by/testSubjects`;
 * a function, a method, a class or anywhere else in a module for `by/subjectTests`.
 *
 * No `textHash`: both are asked from Go to Test, which the platform runs under a modal progress,
 * and a request naming its text would wait there for a `didOpen` the platform only sends outside
 * one. Without it `by` answers about its buffer, which the platform's `didChange`s keep current, and
 * refuses a document the platform has not opened on it yet — which is said as `by` not answering:
 * Go to Test is asked from an editor, which the platform opens the document for when it opens.
 */
data class ByTestLinksParams(val textDocument: TextDocumentIdentifier, val position: Position)

/** A test, or code a test exercises. */
data class ByTestLink(
    /** For a test, its pytest node id after the file, `TestTotal::test_total`; otherwise `Circle.area`. */
    val name: String? = null,
    val kind: SymbolKind? = null,
    /** The module it is declared in. */
    val module: String? = null,
    val uri: String? = null,
    val range: Range? = null,
    /** Its name, which is where to go. */
    val selectionRange: Range? = null,
)

/** The tests that exercise some code, and where a new one goes. */
data class BySubjectTests(
    val tests: List<ByTestLink> = emptyList(),
    /** Absent when the code's module has no importable name, or is a test module itself. */
    val newTest: ByNewTest? = null,
)

/** Where a new test goes, and what it imports — all `by`'s, so Create Test writes nothing it worked out. */
data class ByNewTest(
    /** The test module: `test_<module>.by`, beside the project's other test modules. */
    val uri: String? = null,
    /** The test module exists, so the test is added to it rather than created with it. */
    val exists: Boolean = false,
    /** The module the code is in. */
    val module: String? = null,
    /** The name to import from [module]; absent for a module, which a test imports whole. */
    val import: String? = null,
    /** A name for the test function. */
    val testName: String? = null,
    /** For a test module that exists, the edits that import [import] into it. */
    val importEdits: List<TextEdit> = emptyList(),
)

/**
 * Where the statement to complete is: [position] is the caret, in the text [textHash] names (see
 * [dev.basedpython.pycharm.lsp.ByTextHash]). [options] say how the file indents, as
 * `textDocument/onTypeFormatting` carries them: one level is `tabSize` spaces, or a tab.
 *
 * Field names are the wire format and must match `ty_server`'s `CompleteStatementParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByCompleteStatementParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val options: FormattingOptions,
    val textHash: String,
)

/** The statement completed. */
data class ByCompletedStatement(
    /** Against the text asked about, none overlapping another. */
    val edits: List<TextEdit> = emptyList(),
    /** Where the caret goes, in the text as [edits] leave it. */
    val caret: Position? = null,
)

/**
 * Where to unwrap: the caret or the selection, in the text [textHash] names (see
 * [dev.basedpython.pycharm.lsp.ByTextHash]).
 *
 * Field names are the wire format and must match `ty_server`'s `UnwrapParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByUnwrapParams(val textDocument: TextDocumentIdentifier, val range: Range, val textHash: String)

/**
 * A position inside an empty docstring, in the text [textHash] names. No `format`: `by` writes the
 * format the file's other docstrings are in, and reStructuredText, as PyCharm does, when none says.
 *
 * Field names are the wire format and must match `ty_server`'s `DocstringStubParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByDocstringStubParams(val textDocument: TextDocumentIdentifier, val position: Position, val textHash: String)

/** The docstring to write: [newText] in place of the whole literal at [range], the caret at [caret] after it. */
data class ByDocstringStub(
    val range: Range? = null,
    val newText: String? = null,
    /** In the text as [newText] leaves it: the summary line. */
    val caret: Position? = null,
)

/** The options around a range. */
data class ByUnwrapResponse(
    /** Innermost first; empty when nothing around the range unwraps. */
    val options: List<ByUnwrapOption> = emptyList(),
    /** Why there are no options at all, when it is not for want of a statement: the file does not parse. */
    val refused: String? = null,
)

/** One way to unwrap or remove. */
data class ByUnwrapOption(
    /** `Unwrap 'if...'`, `Remove 'else...'`. */
    val title: String? = null,
    /** What it rewrites: the whole statement for an unwrap, the clause for a removal. */
    val range: Range? = null,
    /** The parts of [range] that stay; empty for a removal. */
    val kept: List<Range> = emptyList(),
    /** The edits, many small ones; absent when the option cannot be applied, and [refused] says why. */
    val edits: List<TextEdit>? = null,
    val refused: String? = null,
)

/**
 * What was copied out of a document: each piece of text with the position it was copied from, in the
 * document's text that `textHash` names ([ByTextHash][dev.basedpython.pycharm.lsp.ByTextHash]). Field
 * names are `ty_server`'s `CopiedImportsParams`, which is `deny_unknown_fields`; the dispatcher takes
 * `textHash` out first.
 */
data class ByCopiedImportsParams(val textDocument: TextDocumentIdentifier, val copies: List<ByCopiedText>, val textHash: String)

/** A piece of copied text, and where its first character was in the document it was copied from. */
data class ByCopiedText(val text: String, val position: Position)

/**
 * A name copied code uses, and the import that binds it: `from <module> import <member> [as <name>]`,
 * or `import <module> [as <name>]` when [member] is absent. Sent back to `by/pastedImports` exactly as
 * `by/copiedImports` answered it.
 */
data class ByCopiedImport(
    val name: String,
    val module: String,
    val member: String? = null,
)

/** The pasted text: where it landed, and the imports it was copied with. As [ByCopiedImportsParams]. */
data class ByPastedImportsParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val imports: List<ByCopiedImport>,
    val textHash: String,
)

/** The imports the paste is missing, and edits to its document that add all of them. */
data class ByPastedImports(
    val imports: List<ByCopiedImport> = emptyList(),
    val edits: List<TextEdit> = emptyList(),
)

/**
 * The member to ask about: a position on the name it is declared with — a `def`'s, or a name a class
 * body assigns or annotates.
 *
 * Field names are the wire format and must match `ty_server`'s `SuperMembersParams`, which is
 * `deny_unknown_fields`; `textHash`, the text [position] is in (see
 * [dev.basedpython.pycharm.lsp.ByTextHash]), is taken out by the server's dispatcher before that.
 */
data class BySuperMembersParams(val textDocument: TextDocumentIdentifier, val position: Position, val textHash: String)

/** One superclass member the member overrides. In MRO order, the nearest along each branch. */
data class BySuperMember(
    val name: String? = null,
    /** The superclass that declares it. */
    val containerName: String? = null,
    val uri: String? = null,
    /** The whole declaration. */
    val range: Range? = null,
    /** The declared name, which is where to go. */
    val selectionRange: Range? = null,
    /** The superclass synthesizes the member rather than writing it; the ranges are the class's. */
    val synthesized: Boolean = false,
    /**
     * The member is abstract where the superclass declares it — an `@abstractmethod`, or a protocol
     * method with no implementation — so that overriding it implements it. Absent from a `by` from
     * before it was said, which reads as not abstract.
     */
    val abstract: Boolean = false,
)

/**
 * The document to answer for, and the text of it to answer for — see
 * [dev.basedpython.pycharm.lsp.ByTextHash].
 *
 * Field names are the wire format and must match `ty_server`'s `DocumentSuperMembersParams`, which
 * is `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByDocumentSuperMembersParams(val textDocument: TextDocumentIdentifier, val textHash: String)

/**
 * The position to ask about, in the text [textHash] names, and whether the expression may call
 * anything: an evaluation the user asked for may, a value tooltip may not — on `f` in `f(x)`, the
 * difference between `f(x)` and `f`.
 *
 * Field names are the wire format and must match `ty_server`'s `EvaluatableExpressionParams`, which
 * is `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByEvaluatableExpressionParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    val calls: Boolean,
    val textHash: String,
)

/** Where the expression is in the document. */
data class ByEvaluatableExpression(val range: Range? = null)

/**
 * The document to count the declarations of, the text of it to count them in, and which counts to
 * work out: `"references"`, `"implementations"`, or both.
 *
 * Field names are the wire format and must match `ty_server`'s `DocumentUsageCountsParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByDocumentUsageCountsParams(
    val textDocument: TextDocumentIdentifier,
    val textHash: String,
    val counts: List<String>,
)

/** A declaration of the document, with the counts asked for. */
data class ByDeclarationUsages(
    val name: String? = null,
    /** `"class"`, `"function"`, or `"method"`, which takes in a class's properties. */
    val kind: String? = null,
    /** The whole declaration, its decorators included. */
    val range: Range? = null,
    /** Its name, where `textDocument/references` and `textDocument/implementation` answer for it. */
    val selectionRange: Range? = null,
    /** How many places use it, the declaration itself not among them. Absent when not asked for. */
    val references: Int? = null,
    /**
     * How many classes subclass a class, directly or not, or how many of their members override a
     * method. Absent when not asked for, and on a function.
     */
    val implementations: Int? = null,
    /**
     * The method is abstract — an `@abstractmethod`, or a protocol method with no implementation — so
     * that what overrides it implements it. Only said of a method with implementations.
     */
    val abstract: Boolean = false,
    /**
     * What [implementations] counts, in the order `textDocument/implementation` answers it: each
     * subclass of a class, or each member overriding a method. Absent from a `by` that predates it.
     */
    val implementors: List<ByImplementor>? = null,
)

/** A subclass of a class, or a member overriding a method, as [ByDeclarationUsages.implementors] gives it. */
data class ByImplementor(
    /** The subclass, or the class whose member overrides. */
    val className: String? = null,
    /** That class's dotted name, `shapes.base.Circle`; absent for a class local to a function. */
    val qualifiedName: String? = null,
    val uri: String? = null,
    val range: Range? = null,
    val selectionRange: Range? = null,
)

/** A class member of the document that overrides at least one superclass member. */
data class ByOverridingMember(
    val name: String? = null,
    /** The class whose body declares it. */
    val containerName: String? = null,
    /** The name it is declared with: where `by/superMembers` answers the same members. */
    val selectionRange: Range? = null,
    /** The member is itself abstract, in the sense of [BySuperMember.abstract]. */
    val abstract: Boolean = false,
    /** What it overrides directly, in MRO order, as `by/superMembers` answers it. */
    val superMembers: List<BySuperMember> = emptyList(),
)

/**
 * Field names are the wire format of `ty_server`'s `OverridableMembersParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class ByOverridableMembersParams(val textDocument: TextDocumentIdentifier, val position: Position, val textHash: String)

/** The class at the position, and the methods it could override. */
data class ByOverridableMembers(
    val className: String? = null,
    /** In MRO order of the superclass each is inherited from. */
    val members: List<ByOverridableMember> = emptyList(),
)

/** A method the class inherits and does not define. */
data class ByOverridableMember(
    /** What `by/overrideMembers` is asked to write it by. */
    val name: String? = null,
    /** The superclass it is inherited from. */
    val containerName: String? = null,
    /** The module that declares the superclass. */
    val containerModule: String? = null,
    /** It is abstract where it is inherited from, so the class has to implement it. */
    val abstract: Boolean = false,
    /** `method`, `classMethod` or `staticMethod`. */
    val kind: String? = null,
    /** The declaration `by/overrideMembers` writes, from its name on: `greet(self, name: str) -> str`. */
    val signature: String? = null,
)

/** Field names are the wire format of `ty_server`'s `OverrideMembersParams`, as [ByOverridableMembersParams]. */
data class ByOverrideMembersParams(
    val textDocument: TextDocumentIdentifier,
    val position: Position,
    /** The [ByOverridableMember.name]s to write. */
    val members: List<String>,
    val textHash: String,
)

/** The declarations, as edits to the document. */
data class ByOverrideMembers(
    /** The declarations and the imports they need; none overlap. */
    val edits: List<TextEdit> = emptyList(),
    /** The first declaration's body, in the document as [edits] leave it. */
    val selection: Range? = null,
)

/** Field names are the wire format of `ty_server`'s `EntryPointParams`, which is `deny_unknown_fields`. */
data class ByEntryPointParams(val uri: String)

/** How a module runs. */
data class ByEntryPointResponse(
    /** The module's last top-level `main`, when it is a `.by` basedpython generates an entry point for. */
    val main: ByMainFunctionReply? = null,
    /** The hand-written `if __name__ == "__main__":` headers. */
    val guards: List<Range> = emptyList(),
)

data class ByMainFunctionReply(
    val nameRange: Range? = null,
    val isAsync: Boolean = false,
    /** The transpiler appends the guard that calls it. */
    val entryPoint: Boolean = false,
    val isPrivate: Boolean = false,
    /** The module calls `main` itself, so no argument parser is generated. */
    val moduleInvokesMain: Boolean = false,
    /** The required parameter the command line cannot supply, which stops `main` being an entry point. */
    val blockedBy: String? = null,
    /** Every parameter but the variadics, in declared order. */
    val parameters: List<ByMainParameterReply> = emptyList(),
    /** The converter unclaimed arguments pass through, when a leading `*rest` asks for them. */
    val extraArguments: String? = null,
    val docstring: String? = null,
)

data class ByMainParameterReply(
    val name: String? = null,
    /** `positional`, `any` or `keyword`. */
    val kind: String? = null,
    val required: Boolean = false,
    val annotation: String? = null,
    val default: String? = null,
    /** How the generated parser registers it; null when the command line cannot fill it. */
    val cli: ByCliParameterReply? = null,
)

data class ByCliParameterReply(
    /** `str`, `int`, `float`, `Path`, `pathlib.Path`; null for a flag pair. */
    val converter: String? = null,
    val choices: List<String>? = null,
    val flags: List<String> = emptyList(),
    val negativeFlags: List<String> = emptyList(),
)

/**
 * One file, or — with [uri] null — every file of every project. Field names are the wire format of
 * `ty_server`'s `TestItemsParams`, which is `deny_unknown_fields`.
 */
data class ByTestItemsParams(val uri: String? = null)

data class ByTestItemsResponse(val files: List<ByTestFileReply> = emptyList())

data class ByTestFileReply(
    val uri: String? = null,
    val tests: List<ByTestItemReply> = emptyList(),
)

data class ByTestItemReply(
    val name: String? = null,
    /** `class` or `function`. */
    val kind: String? = null,
    /** The node id after the file: `TestA::test_b`. */
    val id: String? = null,
    val range: Range? = null,
    val selectionRange: Range? = null,
    val unittest: Boolean = false,
    val children: List<ByTestItemReply> = emptyList(),
)

/** Field names are the wire format of `ty_server`'s `RunModulesParams`, which is `deny_unknown_fields`. */
data class ByRunModulesParams(val module: String? = null)

data class ByRunModulesResponse(val projects: List<ByRunModulesProjectReply> = emptyList())

data class ByRunModulesProjectReply(
    val root: String? = null,
    /** The configured `run.main`. */
    val main: ByRunModuleReply? = null,
    /** [ByRunModulesParams.module], resolved. */
    val requested: ByRunModuleReply? = null,
    val modules: List<ByRunModuleReply> = emptyList(),
)

/** A module name and the file it runs; [uri] is null when the name resolves to nothing `by run` stages. */
data class ByRunModuleReply(
    val module: String? = null,
    val uri: String? = null,
)

/**
 * The path to place, as a URI.
 *
 * Field names are the wire format and must match `ty_server`'s `BuildOutputParams`, which is
 * `deny_unknown_fields`.
 */
data class ByBuildOutputParams(val uri: String)

/** A path's place in its project's build. Every path is absolute. */
data class ByBuildOutput(
    /** The project root: the project a `by build` run anywhere inside it builds. */
    val projectRoot: String? = null,
    /** Where `by build`, run anywhere in the project with no `--out`, writes the project. */
    val buildDirectory: String? = null,
    /** For a `.by` or `.byi` the build is made of, the file it is written to. */
    val generated: String? = null,
    /** For a file in [buildDirectory] a source is written to, that source. */
    val source: String? = null,
)

/**
 * The document to outline, and the text of it to outline — see [dev.basedpython.pycharm.lsp.ByTextHash].
 *
 * Field names are the wire format and must match `ty_server`'s `SyntaxOutlineParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that.
 */
data class BySyntaxOutlineParams(val textDocument: TextDocumentIdentifier, val textHash: String)

/** The whole outline of one document. */
data class BySyntaxOutlineResponse(
    /** The module's statements, in source order. */
    val statements: List<ByOutlineStatement> = emptyList(),
    /** Every string literal part, in source order. */
    val strings: List<ByOutlineString> = emptyList(),
    /** Every comma-separated list of two elements or more, in the order the lists start. */
    val lists: List<ByOutlineList> = emptyList(),
)

/** One statement. */
data class ByOutlineStatement(
    /** From its first token — a decorator, on a decorated definition — to the end of its body. */
    val range: Range? = null,
    /** A compound statement's clauses, in source order; empty on a simple statement. */
    val clauses: List<ByOutlineClause> = emptyList(),
    /** The basedpython modifier keywords a definition was declared with. */
    val modifiers: List<ByOutlineModifier> = emptyList(),
    /** On an expression statement that is a call: the call. */
    val call: ByOutlineCall? = null,
    /** On a `def` or `class`: the name it declares. */
    val name: Range? = null,
    /** On a `def`: the names of its parameters, in source order. */
    val parameters: List<Range> = emptyList(),
    /**
     * On a `def` a class body owns: how the class dispatches it — `instance`, `class` or `static` —
     * which says whether its first parameter is `self`, `cls` or neither, from its decorators,
     * its modifiers and the methods Python makes implicitly static or class methods. Absent on a
     * function, and from a `by` that does not report it.
     */
    val method: String? = null,
    /**
     * On a method: what its first parameter is, the `self` or `cls` an editor writes — `instance` or
     * `class`, `class` for `__new__` too. Absent on any other static method, on a function, and from
     * a `by` that does not report it.
     */
    val receiver: String? = null,
    /**
     * On a simple statement: the expression Enter may break by wrapping it in parentheses — an
     * assignment's value, a `return`'s. Absent where none can be, and from a `by` that does not
     * report it.
     */
    val wrap: Range? = null,
)

/** One clause of a compound statement. */
data class ByOutlineClause(
    /** `if`, `elif`, `case`, the `def` of a function; absent on a trailing lambda's. */
    val keyword: Range? = null,
    /** The `:` that opens the suite; absent when the header has none. */
    val colon: Range? = null,
    /** From the start of the header to the end of the body. */
    val range: Range? = null,
    val body: List<ByOutlineStatement> = emptyList(),
    /** The expression of the header Enter may break by wrapping it in parentheses — an `if`'s condition. */
    val wrap: Range? = null,
)

/** A basedpython modifier keyword, e.g. `frozen data`, and what the parser read it as. */
data class ByOutlineModifier(val range: Range? = null, val name: String? = null)

/** A comma-separated list: a call's arguments, a definition's parameters, a display's elements. */
data class ByOutlineList(
    /** Each element from its first token to its last, a `*` or `**` before it included. */
    val elements: List<Range> = emptyList(),
)

/** A call made as a statement of its own. */
data class ByOutlineCall(
    val callee: Range? = null,
    /** From the first argument to the last; absent when there are none. */
    val arguments: Range? = null,
    /** Whether every argument is positional and none is unpacked. */
    val positionalOnly: Boolean = false,
)

/** One string literal part. */
data class ByOutlineString(
    /** The part, prefix and quotes included. */
    val range: Range? = null,
    /** How many leading characters basedpython strips from every line; absent when none. */
    val strippedIndent: Int? = null,
    /** An f-string's or t-string's `{...}` interpolations, braces included. */
    val interpolations: List<Range> = emptyList(),
    /** The escape sequences in the literal text. */
    val escapes: List<Range> = emptyList(),
    /**
     * Where the literal's text is written: the runs whose text, joined, is what the literal holds —
     * what survives the stripped indentation, without the interpolations. Absent for an empty
     * literal, and from a `by` that predates it.
     */
    val text: List<Range> = emptyList(),
)

/**
 * The document to look through, and the text of it to look through — see
 * [dev.basedpython.pycharm.lsp.ByTextHash].
 *
 * Field names are the wire format and must match `ty_server`'s `InjectionsParams`, which is
 * `deny_unknown_fields`; `textHash` is taken out by the server's dispatcher before that. No range:
 * which fragments a file holds is not a per-screen question — a marker at the top of the file
 * decides a string at the bottom of it — and the answer is cached per document revision on this
 * side anyway.
 */
data class ByInjectionsParams(val textDocument: TextDocumentIdentifier, val textHash: String)

/**
 * `textDocument/semanticTokens/full` about one text of the document — see
 * [dev.basedpython.pycharm.lsp.ByTextHash]. `by` reads `textHash` off any document request; lsp4j
 * writes a subclass's own fields along with its parent's.
 */
class ByNamedSemanticTokensParams(textDocument: TextDocumentIdentifier, val textHash: String) :
    SemanticTokensParams(textDocument)

/** `textDocument/foldingRange` about one text of the document, as [ByNamedSemanticTokensParams]. */
class ByNamedFoldingRangeParams(textDocument: TextDocumentIdentifier, val textHash: String) :
    FoldingRangeRequestParams(textDocument)

/** `textDocument/documentSymbol` about one text of the document, as [ByNamedSemanticTokensParams]. */
class ByNamedDocumentSymbolParams(textDocument: TextDocumentIdentifier, val textHash: String) :
    DocumentSymbolParams(textDocument)

/** `textDocument/implementation` at a position in one text of the document, as [ByNamedSemanticTokensParams]. */
class ByNamedImplementationParams(textDocument: TextDocumentIdentifier, position: Position, val textHash: String) :
    ImplementationParams(textDocument, position)

/** A position on a class's name in one text of the document, for `by/mro`. */
data class ByMroParams(val textDocument: TextDocumentIdentifier, val position: Position, val textHash: String)

/** `textDocument/rename` at a position in one text of the document, as [ByNamedSemanticTokensParams]. */
class ByNamedRenameParams(textDocument: TextDocumentIdentifier, position: Position, newName: String, val textHash: String) :
    RenameParams(textDocument, position, newName)

/** `textDocument/diagnostic` about one text of the document, as [ByNamedSemanticTokensParams]. */
class ByNamedDocumentDiagnosticParams(textDocument: TextDocumentIdentifier, val textHash: String) :
    DocumentDiagnosticParams(textDocument)

/** `textDocument/codeAction` for a range of one text of the document, as [ByNamedSemanticTokensParams]. */
class ByNamedCodeActionParams(textDocument: TextDocumentIdentifier, range: Range, context: CodeActionContext, val textHash: String) :
    CodeActionParams(textDocument, range, context)

/** Every fragment in the document, in source order. */
data class ByInjectionsResponse(val injections: List<ByInjectionFragment> = emptyList())

/** One fragment of another language. */
data class ByInjectionFragment(
    /**
     * The language, as the marker spelled it.
     *
     * `by` does not interpret it and neither should anything before
     * [dev.basedpython.pycharm.lsp.inject.ByInjectedLanguages]: an id no IDE here has support for
     * is an ordinary answer, not an error.
     */
    val language: String? = null,
    /**
     * The runs of the file whose contents, joined in this order, are the fragment's text.
     *
     * One run for an ordinary literal. Several when the fragment was written as adjacent literals,
     * and several when it is a triple-quoted string, whose incidental indentation basedpython
     * strips — the runs are then what is left of each line, so the fragment is the text the program
     * will hold rather than the block as it is laid out in the source.
     */
    val ranges: List<Range> = emptyList(),
    /** What decided the language: `comment`, `declared` or `propagated`. */
    val origin: String? = null,
    /**
     * `by` reports the fragment's syntax errors itself — `invalid-regex` for a regular expression — so
     * an editor's own parser for the language need not. Absent where `by` does not, and from a `by`
     * that predates saying it.
     */
    val checked: Boolean = false,
)

/**
 * The document to look through, how much of it, and the tab size it is drawn with.
 *
 * Field names are the wire format and must match `ty_server`'s `AlignmentGroupsParams`, which is
 * `deny_unknown_fields`. [range] mirrors the one sent to `textDocument/inlayHint` so that both
 * questions are asked about the same span; a group that only partly overlaps it comes back whole,
 * since a column is a property of every member at once.
 */
data class ByAlignmentGroupsParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    /**
     * The columns between tab stops in the editor the hints are drawn in. `by` counts a tab before
     * the `=` to the next stop, so whether two lines share a column depends on it.
     */
    val tabSize: Int,
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
    /**
     * The display column [gapStart] is drawn at: a wide character counts two, a combining mark
     * none, a tab reaches the next multiple of [ByAlignmentGroupsParams.tabSize]. Not
     * `gapStart.character`, which counts UTF-16 units.
     */
    val gapStartColumn: Int,
    /** The display column of the `=`, the same for every member of a group. */
    val gapEndColumn: Int,
)

/**
 * Which files were edited, and which tree is running.
 *
 * Field names are the wire format and must match `ty_server`'s `TranspileForBuildParams`, which is
 * `deny_unknown_fields`.
 */
data class ByTranspileForBuildParams(
    /**
     * Every file of the edit, in one request.
     *
     * One because the tree has one `_by_sourcemap.py` for all of them: an answer about a single
     * file can only carry the tree's map plus that file's entry, and writing several such answers
     * keeps the last one's map and loses every other edit's line table.
     */
    val textDocuments: List<TextDocumentIdentifier>,
    /**
     * The build tree the program is running out of.
     *
     * The IDE knows this and the server cannot: `by run` chooses a temp directory, and the only
     * thing that sees the name is the process that started the program.
     */
    val buildDirectory: String,
)

/**
 * What the edit's slots in the tree should now hold, or every reason they will not be recomputed.
 *
 * One type for both answers because the server sends one untagged shape: [refusals] is set on a
 * refusal and [files] on a success, and exactly one of them is. A refusal is of the whole set — `by`
 * hands back no bytes for part of an edit.
 */
data class ByRestage(
    /** One per distinct file asked about, in the order they were asked. */
    val files: List<ByRestaged>? = null,
    /**
     * The full new text of `_by_sourcemap.py` with the entry of every transpiled file of the set
     * moved, or null when nothing about the map changed.
     *
     * One text for the whole set and written once: it carries every file's entry, which is exactly
     * what a map per file could not.
     */
    val sourcemap: String? = null,
    /** Why the set will not be recomputed. Null when it was. */
    val refusals: List<ByRestageRefusal>? = null,
)

/** One file's slot in the tree. */
data class ByRestaged(
    /** The file in the project this was produced from, as it was asked about. */
    val source: String? = null,
    /** Where the bytes go: absolute, inside the build directory. */
    val generated: String? = null,
    /** The full text to write there. */
    val content: String? = null,
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
)

/** One reason `by` refused the set. */
data class ByRestageRefusal(
    /** The file this is about, or null when it is about the tree and so about every file. */
    val file: String? = null,
    /** Why, written for a user. */
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
    /**
     * The text of the document the answer is about — see [dev.basedpython.pycharm.lsp.ByTextHash].
     * Taken out by the server's dispatcher before the params are read. With it, a `by` that
     * transpiles a document no editor holds answers from the file on disk.
     */
    val textHash: String? = null,
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

    /**
     * The settings `buff` formats a document with, resolved from the configuration that governs it,
     * spelled as the configuration spells them. `null` for a document the editor has not opened; a
     * `buff` from before the request answers `MethodNotFound`.
     */
    @JsonRequest("buff/formatSettings")
    fun formatSettings(args: BuffFormatSettingsParams): CompletableFuture<BuffFormatSettings?>
}

/** The document to ask about. */
data class BuffFormatSettingsParams(val textDocument: TextDocumentIdentifier)

/** What `buff/formatSettings` answers. */
data class BuffFormatSettings(
    val lineLength: Int? = null,
    val indentWidth: Int? = null,
    /** `space` or `tab`. */
    val indentStyle: String? = null,
    /** `double`, `single` or `preserve`. */
    val quoteStyle: String? = null,
    /** `auto`, `lf`, `cr-lf` or `native`. */
    val lineEnding: String? = null,
    /** The URI of the file the settings were read from; `null` when no file configures the document. */
    val configurationFile: String? = null,
)

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
 * A named interface because `LspClientDescriptor.lsp4jServerClass` takes a class, and it has to be
 * one that is both a [org.eclipse.lsp4j.services.LanguageServer] and carries the extension.
 */
interface BuffLanguageServer : org.eclipse.lsp4j.services.LanguageServer, BuffServerExtensions

/**
 * A reference `by/references` answers: where it is, and what it does with the name — `import`,
 * `isinstance`, `typeHint`, `superclass`, `write`, `read`, or `declaration`, the first of them that
 * holds, from the top.
 */
data class ByReference(
    val uri: String? = null,
    val range: Range? = null,
    val kind: String? = null,
)

/** A class of the document, by [selectionRange], its name, and the [members] it inherits. */
data class ByInheritingClass(
    val selectionRange: Range? = null,
    val members: List<ByInheritedMember> = emptyList(),
)

/**
 * A member a class inherits: a document symbol of its superclass's file at [uri], with that
 * superclass as [containerName] and its module as [containerModule].
 */
data class ByInheritedMember(
    val name: String? = null,
    val kind: SymbolKind? = null,
    /** The signature, `(self) -> str`; absent where the symbol has none. */
    val detail: String? = null,
    val containerName: String? = null,
    /** Absent for a superclass in a file no module is imported from. */
    val containerModule: String? = null,
    val uri: String? = null,
    val range: Range? = null,
    val selectionRange: Range? = null,
)

/** A rule `by` reports diagnostics under: the `code` of each of them. */
data class ByRule(
    val name: String = "",
    /** One line. */
    val summary: String? = null,
    /** `ignore`, `warn` or `error`: its level where the configuration names none. */
    val defaultLevel: String? = null,
    /** `stable`, `preview` or `deprecated`. */
    val status: String? = null,
)

/** An HTTP route `by/endpoints` names. */
data class ByEndpoint(
    /** `fastapi` or `django`. */
    val framework: String? = null,
    /** Upper case; empty for a route serving every method, as each of django's does. */
    val methods: List<String> = emptyList(),
    /** The whole path, every prefix it is mounted under included, its parameters as the route writes them. */
    val path: String? = null,
    /** The function, or for a django class-based view the class, that answers it. */
    val handler: ByEndpointSymbol? = null,
    /** Where the route is declared: the decorator, the `add_api_route(...)` or the `path(...)`. */
    val declaration: ByEndpointSymbol? = null,
    /** The FastAPI application serving it, or the urlconf django starts at; absent for a router no application includes. */
    val application: ByEndpointSymbol? = null,
)

/** A declaration by its qualified [name], where it is, and its name's range as [selectionRange]. */
data class ByEndpointSymbol(
    val name: String? = null,
    val uri: String? = null,
    val range: Range? = null,
    val selectionRange: Range? = null,
)
