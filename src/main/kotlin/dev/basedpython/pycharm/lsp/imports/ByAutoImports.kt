package dev.basedpython.pycharm.lsp.imports

import com.google.gson.JsonObject
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClient
import com.intellij.platform.lsp.util.applyTextEdits
import com.intellij.platform.lsp.util.getRangeInDocument
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresEdt
import com.intellij.util.concurrency.annotations.RequiresReadLock
import dev.basedpython.pycharm.lsp.ByServerDocuments
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.askBy
import dev.basedpython.pycharm.lsp.ext.ByNamedCodeActionParams
import dev.basedpython.pycharm.lsp.ext.ByNamedDocumentDiagnosticParams
import dev.basedpython.pycharm.lsp.refactor.ByWorkspaceEditFiles
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionKind
import org.eclipse.lsp4j.CodeActionTriggerKind
import org.eclipse.lsp4j.Command
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.util.WeakHashMap

/** An import `by` says can be added without asking, and the edits to the document that add it. */
internal data class ByAutoImport(val title: String, val edits: List<TextEdit>)

/**
 * Adding an import as the code is typed, when `by` says it is the only one that resolves a name —
 * what *Add unambiguous imports on the fly* does for Java and Kotlin, here for a name `by` reports
 * as an `unresolved-reference`.
 *
 * What is unambiguous is `by`'s call, not this plugin's. `by` offers an import for every module with
 * a member of the unresolved name, and when there is exactly one it marks that quick fix with
 * `"unambiguousImport": true` in its `data`. `isPreferred` cannot carry it: every import that
 * resolves a name is preferred, since each properly fixes the diagnostic. So nothing here works out
 * which module a name comes from or whether another one has it too; it asks for the quick fixes and
 * applies the one marked, or nothing.
 *
 * Every request names the text it is about ([ByTextHash]), and the edit is applied only to that
 * text, so an answer computed before a keystroke is never written into the text after it.
 */
internal object ByAutoImports {

    /** The rule `by` reports a name with no binding under. */
    const val UNRESOLVED_REFERENCE = "unresolved-reference"

    /**
     * Bounds each request. The daemon pass this runs in is restarted by the next edit anyway, so a
     * slow answer is better dropped than waited on.
     */
    private const val TIMEOUT_MS = 2_000

    /**
     * The import that resolves the name `by` reports unresolved at [offset] of [document], if `by`
     * says there is only one. [caret], when given, is where the user is typing: a name the caret is
     * in or right after is still being typed, and is not imported until the user has moved on.
     *
     * Asks `by` for the document's diagnostics, once per revision of the document (see
     * [ByPulledDiagnostics]), and then for the quick fixes of the one at [offset].
     */
    @RequiresBackgroundThread
    @RequiresReadLock
    fun find(server: LspClient, file: VirtualFile, document: Document, offset: Int, caret: Int?): ByAutoImport? {
        ByServerDocuments.ensureOpen(server, server.project, file)
        val identifier = server.getDocumentIdentifier(file)
        val textHash = ByTextHash.of(document.immutableCharSequence)
        val diagnostics = ByPulledDiagnostics.getInstance(server.project).of(document) {
            server.askBy("textDocument/diagnostic", TIMEOUT_MS) {
                it.textDocumentService.diagnostic(ByNamedDocumentDiagnosticParams(identifier, textHash))
            }.value?.let { report ->
                if (report.isRelatedFullDocumentDiagnosticReport) report.relatedFullDocumentDiagnosticReport.items.orEmpty() else null
            }
        } ?: return null
        val (diagnostic, range) = unresolvedAt(diagnostics, document, offset) ?: return null
        if (caret != null && caret in range.startOffset..range.endOffset) return null

        val context = CodeActionContext(listOf(diagnostic)).apply {
            only = listOf(CodeActionKind.QuickFix)
            triggerKind = CodeActionTriggerKind.Automatic
        }
        val actions = server.askBy("textDocument/codeAction", TIMEOUT_MS) {
            it.textDocumentService.codeAction(ByNamedCodeActionParams(identifier, diagnostic.range, context, textHash))
        }.value ?: return null
        // `by` writes the URI of the file it edits itself, which need not be spelled as it was sent
        return unambiguousImport(actions) { uri -> uri == identifier.uri || ByWorkspaceEditFiles.fileOf(uri) == file }
    }

    /**
     * The `unresolved-reference` of [diagnostics] whose range holds [offset], and that range in
     * [document]. The platform asks at the start of a problem it has drawn, which may have been
     * drawn from an earlier answer and moved with the edits since; this answer is the current one.
     */
    fun unresolvedAt(diagnostics: List<Diagnostic>, document: Document, offset: Int): Pair<Diagnostic, TextRange>? =
        diagnostics.firstNotNullOfOrNull { diagnostic ->
            if (codeOf(diagnostic) != UNRESOLVED_REFERENCE) return@firstNotNullOfOrNull null
            val range = getRangeInDocument(document, diagnostic.range) ?: return@firstNotNullOfOrNull null
            if (offset in range.startOffset..range.endOffset) diagnostic to range else null
        }

    /**
     * The one action of [actions] `by` marks as the only import that resolves the name, as edits to
     * this document — [isThisDocument] says which URI that is.
     *
     * Nothing when no action is marked, which is what a `by` from before the mark answers, and
     * nothing when more than one is: `by` marks one fix for one diagnostic, and two would be two
     * meanings to choose between. An action that does anything but edit this document — runs a
     * command, creates a file, touches another file — is not one to apply unasked, and is nothing
     * too.
     */
    fun unambiguousImport(actions: List<Either<Command, CodeAction>>, isThisDocument: (String) -> Boolean): ByAutoImport? {
        val action = actions.mapNotNull { if (it.isRight) it.right else null }.filter(::isUnambiguousImport).singleOrNull()
            ?: return null
        if (action.command != null || action.disabled != null) return null
        val edit = action.edit ?: return null
        if (!edit.documentChanges.isNullOrEmpty()) return null
        val changes = edit.changes ?: return null
        val (uri, edits) = changes.entries.singleOrNull() ?: return null
        if (!isThisDocument(uri) || edits.isNullOrEmpty()) return null
        return ByAutoImport(action.title, edits)
    }

    /** Whether `by` marked [action] as the only import that resolves the name it fixes. */
    fun isUnambiguousImport(action: CodeAction): Boolean {
        val data = action.data as? JsonObject ?: return false
        val mark = data.get("unambiguousImport") ?: return false
        return mark.isJsonPrimitive && mark.asJsonPrimitive.isBoolean && mark.asBoolean
    }

    /**
     * Adds [import] to [document], as one step to undo, provided the document is still the
     * revision [stamp] it was worked out for. Says whether it did.
     */
    @RequiresEdt
    fun apply(project: Project, document: Document, stamp: Long, import: ByAutoImport): Boolean {
        if (project.isDisposed || document.modificationStamp != stamp) return false
        return WriteCommandAction.writeCommandAction(project)
            .withName(import.title)
            .compute<Boolean, RuntimeException> { applyTextEdits(document, import.edits) }
    }

    private fun codeOf(diagnostic: Diagnostic): String? =
        diagnostic.code?.let { if (it.isLeft) it.left else it.right?.toString() }
}

/**
 * `by`'s diagnostics for a document, kept for the revision they were asked at.
 *
 * The platform asks [ByReferenceImporter] once for each problem it has drawn, and the answer for
 * every one of them starts with the same question — which problems `by` reports in this text — so
 * it is asked once per revision rather than once per problem.
 *
 * Weakly keyed by the document, and a project service, so neither outlives what it is about.
 */
@Service(Service.Level.PROJECT)
internal class ByPulledDiagnostics {

    private class Pulled(val stamp: Long, val diagnostics: List<Diagnostic>)

    private val pulled = WeakHashMap<Document, Pulled>()

    /**
     * The diagnostics of [document] at its current revision, asked with [ask] unless this revision's
     * are kept. `null` when [ask] could not say, which is not kept.
     */
    @RequiresReadLock
    fun of(document: Document, ask: () -> List<Diagnostic>?): List<Diagnostic>? {
        val stamp = document.modificationStamp
        synchronized(pulled) {
            pulled[document]?.takeIf { it.stamp == stamp }?.let { return it.diagnostics }
        }
        val diagnostics = ask() ?: return null
        synchronized(pulled) { pulled[document] = Pulled(stamp, diagnostics) }
        return diagnostics
    }

    companion object {
        fun getInstance(project: Project): ByPulledDiagnostics = project.service()
    }
}
