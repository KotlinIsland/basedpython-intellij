package dev.basedpython.pycharm.run.testmarker

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.ByTestItem
import dev.basedpython.pycharm.run.model.walk
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Puts a "run test" gutter icon next to the tests in a `.by` file, and says how many tests each
 * icon would run.
 *
 * What counts as a test is `by/testItems`' answer ([ByProgramModel.testItems]): the checker's static
 * model of pytest's collection rules. So a `def test_helper` nested in a function gets no icon, nor
 * does a method of a `Test` class with its own `__init__`, while a `unittest.TestCase` method gets
 * one whatever it is called — and none of it runs pytest, which would import, and so execute, the
 * test modules just to draw an icon.
 *
 * Clicking runs the item on that line through [dev.basedpython.pycharm.run.ByTestFromFileProducer]
 * (resolved via [ExecutorAction.getActions]), which reads the same answer.
 *
 * The PSI is flat (token leaves only), so an icon goes on the first non-whitespace leaf of the line
 * the item's name is on, and on no other leaf of it.
 */
class ByTestRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        // Only fire on real leaves (no children) to mirror the per-leaf contract.
        if (element.firstChild != null) return null

        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (virtualFile.extension != "by") return null

        val document = PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null
        val offset = element.textRange.startOffset
        if (offset >= document.textLength) return null
        val line = document.getLineNumber(offset)
        val lineStart = document.getLineStartOffset(line)
        val lineText = document.charsSequence.subSequence(lineStart, document.getLineEndOffset(line))
        val firstContent = lineText.indexOfFirst { !it.isWhitespace() }
        // A blank line declares nothing, and `indexOfFirst` would answer -1.
        if (firstContent < 0 || offset != lineStart + firstContent) return null

        val items = ByProgramModel.getInstance(element.project).testItems(virtualFile) ?: return null
        val item = items.walk().firstOrNull { it.selectionRange.start.line == line } ?: return null

        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) { tooltip(item) }
    }

    /**
     * A count only earns its place when running the line means running more than the one thing it
     * names: a class is its tests.
     */
    private fun tooltip(item: ByTestItem): String = when {
        item.isClass && item.testCount > 1 -> BasedPythonBundle.message("testMarker.runTests", item.testCount)
        else -> BasedPythonBundle.message("testMarker.run")
    }
}
