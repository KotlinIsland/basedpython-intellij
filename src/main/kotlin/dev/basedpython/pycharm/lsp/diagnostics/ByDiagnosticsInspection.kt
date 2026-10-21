package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.CommonProblemDescriptor
import com.intellij.codeInspection.GlobalInspectionContext
import com.intellij.codeInspection.GlobalInspectionTool
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.ProblemDescriptionsProcessor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiManager
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position

/**
 * `by`'s diagnostics for every file in an inspection's scope: what *Code | Inspect Code* lists, and
 * what an offline or Qodana run reports.
 *
 * A global inspection because the answer is the whole project's at once — one `by/checkWorkspace`
 * for the run, not one request per file — and because a global inspection runs only in a batch, so
 * the editor, which already shows `by`'s diagnostics for an open file through the LSP client, is
 * not given them a second time.
 *
 * `by/checkWorkspace` rather than `workspace/diagnostic`: the long poll answers only once something
 * changes, and a run over a project with nothing wrong in it would wait on it forever. See
 * [dev.basedpython.pycharm.lsp.ext.ByServerExtensions.checkWorkspace].
 *
 * In a batch run nothing has opened a file, so nothing has started `by`; the run starts it.
 */
internal class ByDiagnosticsInspection : GlobalInspectionTool() {

    /** Waits on the server, which must not be done under a read lock; it takes its own for the PSI. */
    override fun isReadActionNeeded(): Boolean = false

    override fun isGraphNeeded(): Boolean = false

    override fun runInspection(
        scope: AnalysisScope,
        manager: InspectionManager,
        globalContext: GlobalInspectionContext,
        processor: ProblemDescriptionsProcessor,
    ) {
        val project = globalContext.project
        val (client, report) = when (val checked = runBlockingCancellable { ByWorkspaceCheck.check(project) }) {
            is ByWorkspaceCheck.Report -> checked.client to checked.report
            // Said in the results, where the run's reader looks for `by`'s findings: an empty
            // list would read as a project with nothing wrong in it.
            is ByWorkspaceCheck.Not -> {
                processor.addProblemElement(
                    globalContext.refManager.refProject,
                    manager.createProblemDescriptor(checked.why),
                )
                return
            }
        }
        for (item in report.items) {
            // Unchanged reports answer result ids, and this run sent none.
            if (!item.isWorkspaceFullDocumentDiagnosticReport) continue
            val full = item.workspaceFullDocumentDiagnosticReport
            val diagnostics = full.items.orEmpty().filter { ByProjectProblem.severityOf(it) != null }
            if (diagnostics.isEmpty()) continue
            ProgressManager.checkCanceled()
            ReadAction.runBlocking<RuntimeException> {
                val file = client.descriptor.findFileByUri(full.uri) ?: return@runBlocking
                if (!scope.contains(file)) return@runBlocking
                val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@runBlocking
                val document = FileDocumentManager.getInstance().getDocument(file) ?: return@runBlocking
                val descriptors = diagnostics.map { d ->
                    manager.createProblemDescriptor(
                        psiFile,
                        rangeOf(document, d),
                        ByProjectProblem.textOf(d),
                        highlightTypeOf(ByProjectProblem.severityOf(d)!!),
                        false,
                    )
                }
                processor.addProblemElement(
                    globalContext.refManager.getReference(psiFile),
                    *descriptors.toTypedArray<CommonProblemDescriptor>(),
                )
            }
        }
    }

    companion object {
        /**
         * [diagnostic]'s range in [document], clamped to it: the report is of the text the server
         * checked, and the document can have moved on since.
         */
        fun rangeOf(document: Document, diagnostic: Diagnostic): TextRange {
            val start = offsetOf(document, diagnostic.range.start)
            val end = offsetOf(document, diagnostic.range.end).coerceAtLeast(start)
            // a point — a missing token, say — is shown on the character it stands before
            if (end == start && start < document.textLength) return TextRange(start, start + 1)
            return TextRange(start, end)
        }

        private fun offsetOf(document: Document, position: Position): Int {
            if (document.lineCount == 0) return 0
            val line = position.line.coerceIn(0, document.lineCount - 1)
            val lineStart = document.getLineStartOffset(line)
            return (lineStart + position.character).coerceAtMost(document.getLineEndOffset(line))
        }

        /** How an inspection result is shown, from the severity the editor shows it at. */
        fun highlightTypeOf(severity: HighlightSeverity): ProblemHighlightType = when {
            severity >= HighlightSeverity.ERROR -> ProblemHighlightType.GENERIC_ERROR
            severity >= HighlightSeverity.WARNING -> ProblemHighlightType.WARNING
            else -> ProblemHighlightType.WEAK_WARNING
        }
    }
}
