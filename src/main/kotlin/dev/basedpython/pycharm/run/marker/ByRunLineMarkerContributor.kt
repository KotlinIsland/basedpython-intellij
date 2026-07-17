package dev.basedpython.pycharm.run.marker

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Puts a green "run" gutter icon on `if __name__ == "__main__":` lines and top-level
 * `def main(` / `async def main(` declarations in `.by` files. Clicking runs the file's
 * `by run <module>` configuration via [dev.basedpython.pycharm.run.ByRunFromFileProducer].
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
        val lineEnd = document.getLineEndOffset(lineNumber)
        val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd))

        if (!lineText.isRunnableLine()) return null

        // Only the first non-whitespace leaf of the line gets the icon.
        val firstContentOffset = lineStart + lineText.indexOfFirst { !it.isWhitespace() }
        if (offset != firstContentOffset) return null

        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            ExecutorAction.getActions(0),
        ) { "Run with by" }
    }
}

private val MAIN_GUARD = Regex("""^\s*if\s+__name__\s*==\s*(['"])__main__\1\s*:.*$""")
// Top-level only: no leading indentation.
private val MAIN_DEF = Regex("""^(async\s+)?def\s+main\s*\(.*$""")

private fun String.isRunnableLine(): Boolean =
    MAIN_GUARD.matches(this) || MAIN_DEF.matches(this)
