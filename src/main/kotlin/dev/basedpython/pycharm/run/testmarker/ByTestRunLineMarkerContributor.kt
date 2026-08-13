package dev.basedpython.pycharm.run.testmarker

import com.intellij.execution.lineMarker.ExecutorAction
import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.run.test.ByDeclarationPath
import dev.basedpython.pycharm.run.test.ByTestDeclarations
import dev.basedpython.pycharm.run.test.node.ByTestLookup
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Puts a "run test" gutter icon next to the tests in a `.by` file, and says how many tests each
 * icon would run.
 *
 * What counts as a test is [ByTestLookup]'s verdict — pytest's own `--collect-only`, rather than a
 * guess about names. That buys two things a regex cannot have:
 *
 *  - a `def test_helper` pytest does *not* collect (nested in another function, in a directory
 *    `norecursedirs` skips, in a file that is not `test_*.py`) gets no icon claiming it is runnable;
 *  - a test that does not look like one — a project setting `python_functions` to something else,
 *    a class collected through a plugin — gets an icon anyway.
 *
 * Until something has been collected the fallback is pytest's default naming convention, which is
 * what this contributor did for every file before the node view existed.
 *
 * Clicking runs the declaration on that line through
 * [dev.basedpython.pycharm.run.ByTestFromFileProducer] (resolved via [ExecutorAction.getActions]),
 * which decides what to run from the same verdict.
 *
 * The PSI is flat (token leaves only), so detection is done against the raw document line text. To
 * avoid duplicate icons, a non-null [Info] is returned only for the FIRST non-whitespace leaf of
 * the matching line — mirroring [dev.basedpython.pycharm.run.marker.ByRunLineMarkerContributor].
 */
class ByTestRunLineMarkerContributor : RunLineMarkerContributor() {

    override fun getInfo(element: PsiElement): Info? {
        // Only fire on real leaves (no children) to mirror the per-leaf contract.
        if (element.firstChild != null) return null

        val file = element.containingFile ?: return null
        val virtualFile = file.virtualFile ?: return null
        if (virtualFile.extension != "by") return null

        val document: Document =
            PsiDocumentManager.getInstance(element.project).getDocument(file) ?: return null

        val offset = element.textRange.startOffset
        if (offset >= document.textLength) return null
        val line = document.getLineNumber(offset)
        val lineText = document.lineText(line)

        val firstContent = lineText.indexOfFirst { !it.isWhitespace() }
        // A blank line declares nothing, and `indexOfFirst` would answer -1.
        if (firstContent < 0) return null
        // Only the first non-whitespace leaf of the line gets the icon.
        if (offset != document.getLineStartOffset(line) + firstContent) return null

        val declaration = ByTestDeclarations.declarationAt(
            lineText = { document.lineText(it) },
            lineCount = document.lineCount,
            line = line,
        ) ?: return null

        // A `.by` file with declarations in it is on screen, so this is the moment the question is
        // worth the subprocess. The first answer is painted from the naming convention and redrawn
        // when the collection lands; see [ByTestLookup.ensureCollected].
        ByTestLookup.ensureCollected(element.project)

        val verdict = ByTestLookup.verdict(element.project, virtualFile, declaration)
        if (verdict is ByTestLookup.Verdict.NotATest) return null

        return Info(AllIcons.RunConfigurations.TestState.Run, ExecutorAction.getActions(0)) {
            tooltip(verdict, declaration)
        }
    }

    /**
     * A count only earns its place when running the line means running more than the one thing it
     * names: `test_add` is just a test, while a parametrized one is its cases and a class is its
     * methods.
     */
    private fun tooltip(verdict: ByTestLookup.Verdict, declaration: ByDeclarationPath): String {
        val count = (verdict as? ByTestLookup.Verdict.Tests)?.count ?: 0
        return when {
            count <= 1 -> BasedPythonBundle.message("testMarker.run")
            declaration.isClass -> BasedPythonBundle.message("testMarker.runTests", count)
            else -> BasedPythonBundle.message("testMarker.runCases", count)
        }
    }
}

/** Text of [line], without its line break. */
private fun Document.lineText(line: Int): String =
    getText(TextRange(getLineStartOffset(line), getLineEndOffset(line)))
