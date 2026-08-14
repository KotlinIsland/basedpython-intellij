package dev.basedpython.pycharm.run.marker

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.run.main.ByMainArgumentHistory
import dev.basedpython.pycharm.run.main.ByMainArguments
import dev.basedpython.pycharm.run.main.ByMainFunction
import dev.basedpython.pycharm.run.main.ByMainSignature
import dev.basedpython.pycharm.run.main.ByRunWithArgumentsAction
import dev.basedpython.pycharm.run.main.lineTextAt
import dev.basedpython.pycharm.run.moduleNameFor

/**
 * Puts a green "run" gutter icon on `if __name__ == "__main__":` lines and top-level
 * `def main(` / `async def main(` declarations in `.by` files. Clicking runs the file's
 * `by run <module>` configuration via [dev.basedpython.pycharm.run.ByRunFromFileProducer].
 *
 * A `main` with parameters is a program with a command-line interface — basedpython turns the
 * signature into one — so the popup also offers [ByRunWithArgumentsAction], for changing arguments
 * a run would otherwise reuse without asking. Arguments that are *missing* need no gutter affordance
 * at all: [dev.basedpython.pycharm.run.ByRunConfiguration] asks for those as the run starts, however
 * it was started. The tooltip says which of the three situations the line is in, because the
 * difference is otherwise only visible after the run: arguments to fill, arguments already
 * remembered, or a `main` that is no entry point at all.
 *
 * The PSI is flat (token leaves only), so detection is done against the raw document line
 * text. To avoid duplicate icons, a non-null [Info] is returned only for the FIRST leaf of
 * the matching line.
 */
class ByRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        // Only fire on real leaves (no children) to mirror the per-leaf contract.
        if (element.firstChild != null) return null

        val file = element.containingFile ?: return null
        if (file.virtualFile?.extension != "by") return null

        val document: Document =
            PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null

        val offset = element.textRange.startOffset
        if (offset >= document.textLength) return null
        val lineNumber = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(lineNumber)
        val lineText = document.lineTextAt(lineNumber)

        val isDefinition = ByMainSignature.MAIN_DEF.containsMatchIn(lineText)
        if (!isDefinition && !ByMainSignature.MAIN_GUARD.matches(lineText)) return null

        // Only the first non-whitespace leaf of the line gets the icon.
        val firstContentOffset = lineStart + lineText.indexOfFirst { !it.isWhitespace() }
        if (offset != firstContentOffset) return null

        // A module that invokes `main` itself keeps its own entry point: basedpython generates no
        // argument parser for it, so there is nothing here to fill in.
        val main = if (isDefinition && !ByMainSignature.invokesMain(document::lineTextAt, document.lineCount)) {
            ByMainSignature.at(document::lineTextAt, document.lineCount, lineNumber)
        } else {
            null
        }
        val remembered = main?.let { remembered(element, it) }

        return Info(AllIcons.RunConfigurations.TestState.Run, actions(main)) { tooltip(main, remembered) }
    }

    /**
     * The arguments a run started from this gutter would carry: whatever the module was last run
     * with, unless that no longer fills everything the signature now requires.
     */
    private fun remembered(element: PsiElement, main: ByMainFunction): String? {
        if (!main.takesArguments) return null
        val file = element.containingFile?.virtualFile ?: return null
        val module = moduleNameFor(element.project, file) ?: return null
        val last = ByMainArgumentHistory.last(element.project, module) ?: return null
        return last.takeIf { ByMainArguments.missing(main, it).isEmpty() }
    }

    /**
     * Run and Debug first, then the form.
     *
     * The form is not the way out of a run that cannot start — [ByRunConfiguration] asks for
     * missing arguments itself, so plain Run is never the wrong choice. What this adds is the way
     * to *change* arguments a run would otherwise reuse without asking.
     */
    private fun actions(main: ByMainFunction?): Array<AnAction> {
        val standard = ExecutorAction.getActions(0)
        if (main == null || !main.takesArguments) return standard
        return standard + WITH_ARGUMENTS
    }

    private fun tooltip(main: ByMainFunction?, remembered: String?): String {
        if (main == null) return RUN
        val blocked = main.blockedBy
        val required = main.required
        return when {
            blocked != null -> "`main` is not an entry point: `${blocked.name}` cannot come from " +
                "the command line, so running this module does nothing"
            remembered != null -> "$RUN — $remembered"
            required.isNotEmpty() -> "$RUN — `main` requires ${required.joinToString(", ") { it.name }}"
            else -> RUN
        }
    }

    private companion object {
        const val RUN = "Run with by"

        /**
         * One instance for every marker: [Info] compares its actions, and a fresh action on each
         * pass would make otherwise identical markers look like new ones.
         */
        val WITH_ARGUMENTS = ByRunWithArgumentsAction()
    }
}
