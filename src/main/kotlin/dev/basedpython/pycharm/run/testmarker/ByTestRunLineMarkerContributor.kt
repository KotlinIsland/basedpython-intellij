package dev.basedpython.pycharm.run.testmarker

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement

/**
 * Puts a green "run test" gutter icon next to pytest-style test declarations in `.by` files:
 * `def test_...` / `async def test_...` functions and `class Test...` classes. Clicking it runs
 * the test on that line through [dev.basedpython.pycharm.run.ByTestFromFileProducer], which
 * builds a `by run pytest` configuration for that node id (resolved via
 * [ExecutorAction.getActions]).
 *
 * The PSI is flat (token leaves only), so detection is done against the raw document line text.
 * To avoid duplicate icons, a non-null [Info] is returned only for the FIRST non-whitespace leaf
 * of the matching line — mirroring [dev.basedpython.pycharm.run.marker.ByRunLineMarkerContributor].
 */
class ByTestRunLineMarkerContributor : RunLineMarkerContributor() {

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

        if (!lineText.isTestLine()) return null

        // Only the first non-whitespace leaf of the line gets the icon.
        val firstContentOffset = lineStart + lineText.indexOfFirst { !it.isWhitespace() }
        if (offset != firstContentOffset) return null

        return Info(
            AllIcons.RunConfigurations.TestState.Run,
            ExecutorAction.getActions(0),
        ) { "Run test with pytest" }
    }
}

// pytest-style test function: `def test_...(` or `async def test_...(`, at any indentation.
private val TEST_DEF = Regex("""^\s*(async\s+)?def\s+test_\w*\s*\(.*$""")
// pytest-style test class: `class Test...`, at any indentation.
private val TEST_CLASS = Regex("""^\s*class\s+Test\w*\s*[(:].*$""")

private fun String.isTestLine(): Boolean =
    TEST_DEF.matches(this) || TEST_CLASS.matches(this)
