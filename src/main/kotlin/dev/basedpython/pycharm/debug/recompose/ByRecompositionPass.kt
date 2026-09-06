package dev.basedpython.pycharm.debug.recompose

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.debug.dfa.ByDataFlowVerdictRenderer
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Draws, on the definition line of every composable that ran in the latest frame, how many times
 * it ran and the first reason: `ran ×2 · count 0 → 2, set at counter.by:14`.
 *
 * The data-flow pattern, for the data-flow reasons: a highlighting pass over a project service, so
 * the daemon owns when it runs and [ByRecompositionSession] restarts it when a stop changes what
 * is known; and a label painted in the margin past the end of the line rather than an inlay, so
 * nothing reflows under someone reading code while stopped in a debugger.
 *
 * Drawn only while the program is held at a stop. The labels describe the frame the program last
 * finished before it stopped, and once it runs on that is a claim about a moment that has gone —
 * the session clears them on resume and the pass draws nothing. What the pass drew is also
 * removable without a pass ([ByRecompositionMarks]), for the two moments no pass will come: the
 * setting turned off, and a session ending while it is off.
 */
class ByRecompositionPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {

    override fun registerHighlightingPassFactory(
        registrar: TextEditorHighlightingPassRegistrar,
        project: Project,
    ) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        if (file.language != BasedPythonLanguage) return null
        // Checked here as well as at every stop: a session recorded while the feature was on
        // must not draw once it is off. The labels already on screen at that moment are the
        // session's to remove (ByRecompositionSession.settingChanged), since this returns null
        if (!BasedPythonSettings.getInstance(file.project).debuggerRecompositions) return null
        return ByRecompositionPass(file.project, editor, file)
    }
}

/** How a composable definition that ran is marked. */
object ByRecompositionColors {
    /**
     * The definition line of a composable that ran in the latest frame.
     *
     * No attributes of its own: the label in the margin is the information, and the line is still
     * code somebody is reading. The key exists so a scheme *can* say something about it, and so
     * the highlighter can be told apart from every other one on the line.
     */
    @JvmField
    val RAN: TextAttributesKey = TextAttributesKey.createTextAttributesKey("BASEDPYTHON_RECOMPOSITION_RAN")
}

/**
 * What the pass drew in an editor, and the one way to take it down — from the pass before it draws
 * again, or from the session when no pass is coming. EDT, as the markup model is.
 */
internal object ByRecompositionMarks {

    private val DRAWN: Key<List<RangeHighlighter>> = Key.create("basedpython.recompose.drawn")

    /** Remove everything drawn in [editor], and forget it. Nothing drawn costs nothing. */
    fun clear(editor: Editor) {
        val drawn = editor.getUserData(DRAWN) ?: return
        editor.putUserData(DRAWN, null)
        val markup = editor.markupModel
        for (highlighter in drawn) if (highlighter.isValid) markup.removeHighlighter(highlighter)
    }

    /** Remember what a pass just drew, having cleared what it drew before. */
    fun drawn(editor: Editor, highlighters: List<RangeHighlighter>) {
        editor.putUserData(DRAWN, highlighters.ifEmpty { null })
    }
}

/**
 * One above the LSP semantic tokens, which the daemon puts at `WEAK_WARNING` (3750, measured in a
 * running IDE), and below a warning: the same layer the data-flow findings draw at, for the same
 * reasons — see `ByDataFlowPass`.
 */
private const val OVER_SEMANTIC_TOKENS: Int = HighlighterLayer.WEAK_WARNING + 1

private class ByRecompositionPass(
    project: Project,
    private val editor: Editor,
    private val file: PsiFile,
) : TextEditorHighlightingPass(project, editor.document, false) {

    private var labels: List<ByMarginLabel> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        val virtualFile = file.originalFile.virtualFile ?: return
        labels = ByRecompositionSession.getInstance(myProject).labelsFor(virtualFile)
    }

    override fun doApplyInformationToEditor() {
        ByRecompositionMarks.clear(editor)
        if (labels.isEmpty()) return
        val markup = editor.markupModel
        val document = editor.document
        val drawn = labels.mapNotNull { label ->
            // A line past the end of the document is one the file no longer has: the record was
            // made against text that has since been edited, and drawing it clamped would mark
            // code it was never about
            val line = label.line - 1
            if (line < 0 || line >= document.lineCount) return@mapNotNull null
            markup.addRangeHighlighter(
                ByRecompositionColors.RAN,
                document.getLineStartOffset(line),
                document.getLineEndOffset(line),
                OVER_SEMANTIC_TOKENS,
                HighlighterTargetArea.EXACT_RANGE,
            ).also { it.customRenderer = ByDataFlowVerdictRenderer(label.text) }
        }
        ByRecompositionMarks.drawn(editor, drawn)
    }
}
