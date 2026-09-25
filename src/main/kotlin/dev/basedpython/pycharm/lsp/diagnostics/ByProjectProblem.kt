package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.analysis.problemsView.FileProblem
import com.intellij.analysis.problemsView.ProblemsProvider
import com.intellij.codeHighlighting.HighlightDisplayLevel
import com.intellij.icons.AllIcons
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.vfs.VirtualFile
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DiagnosticSeverity
import javax.swing.Icon

/**
 * One diagnostic `by` reported for a file, as a row of the Problems view's Project Errors tab.
 *
 * A plain [FileProblem], and deliberately not a `HighlightingDuplicateProblem`, the marker that
 * would have the tab drop these rows for a file while the editor's highlighting lists it. Measured
 * on 263.5153, the editor's rows never reach Project Errors — they are the Current File tab's — so
 * nothing would stand in for the rows dropped, and nothing tells a provider when to put them back.
 * An open file is listed here as every other file is; the editor's own tab and gutter are where its
 * keystroke-fresh diagnostics are, and this tab never lists a diagnostic twice.
 *
 * Compared by identity. The collector keeps a set of what it was told, and a row is taken out by
 * handing back the instance that was put in; two diagnostics with the same text at the same place
 * are still two rows.
 */
internal class ByProjectProblem(
    override val provider: ProblemsProvider,
    override val file: VirtualFile,
    override val text: String,
    /** What *Group by Inspection* groups on: the rule that fired, the nearest thing `by` has. */
    override val group: String?,
    /** Zero-based, as the Problems view counts. */
    override val line: Int,
    /** Zero-based, in UTF-16 units, which is what LSP counts in unless told otherwise and Java does. */
    override val column: Int,
    val severity: HighlightSeverity,
) : FileProblem {

    /** The icon the editor's own rows carry at this severity. */
    override val icon: Icon get() = HighlightDisplayLevel.find(severity)?.icon ?: AllIcons.General.Information

    override fun toString(): String = "$file:${line + 1}:${column + 1}: $text"

    companion object {
        /**
         * The row for [diagnostic], or `null` for one that is not a problem.
         *
         * A diagnostic of severity *hint* is how `by` marks an unused name or an unreachable line so
         * an editor can grey it out: a rendering instruction, not a finding, and `by check` does not
         * report them. They are about a fifth of what a whole-project check returns (4,911 of
         * 25,522 in an 8,664-file project), and listing them would bury the problems.
         */
        fun of(provider: ProblemsProvider, file: VirtualFile, diagnostic: Diagnostic): ByProjectProblem? {
            val severity = severityOf(diagnostic) ?: return null
            val start = diagnostic.range.start
            return ByProjectProblem(
                provider, file, textOf(diagnostic), codeOf(diagnostic), start.line, start.character, severity,
            )
        }

        /**
         * The severity the editor shows [diagnostic] at, or `null` for a hint, which is not a problem.
         *
         * Asked of the same [ByDiagnosticsSupport] the editor's annotations go through, so a row here
         * and the highlighting in the open file agree on what is an error.
         */
        fun severityOf(diagnostic: Diagnostic): HighlightSeverity? =
            if (diagnostic.severity == DiagnosticSeverity.Hint) null else SUPPORT.getHighlightSeverity(diagnostic)

        /** The message as plain text, as the editor's annotation carries it. */
        fun textOf(diagnostic: Diagnostic): String = SUPPORT.getMessage(diagnostic)

        fun codeOf(diagnostic: Diagnostic): String? = diagnostic.code?.let { it.left ?: it.right?.toString() }

        /** Holds nothing; its methods are the platform's reading of a diagnostic, and this plugin's. */
        private val SUPPORT = ByDiagnosticsSupport()
    }
}
