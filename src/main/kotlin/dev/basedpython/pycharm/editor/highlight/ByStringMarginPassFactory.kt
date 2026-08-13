package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage

/**
 * Keeps every open `.by` editor's trim margins up to date, as a daemon pass.
 *
 * A highlighting pass, not an annotator: an annotation is text attributes over a range, and the
 * margin is a line drawn where there may be no text (see [ByStringMarginRenderer]). This is the
 * same shape the platform gives its own indent guides — compute off the EDT, reconcile markup on
 * it — and it inherits the daemon's cancellation, its restart-on-edit and its per-editor lifetime
 * for free.
 *
 * Registered for the basedpython language only. A `.py` file that PyCharm still owns is real
 * Python, where a triple-quoted literal *is* its content and nothing is trimmed; one this plugin
 * has claimed (see `lang.dialect.BasedPythonFileTypeOverrider`) reaches here as basedpython and
 * gets the margin, which is the right answer for both.
 */
class ByStringMarginPassFactory : TextEditorHighlightingPassFactory, TextEditorHighlightingPassFactoryRegistrar {

    override fun registerHighlightingPassFactory(
        registrar: TextEditorHighlightingPassRegistrar,
        project: Project,
    ) {
        registrar.registerTextEditorHighlightingPass(this, null, null, false, -1)
    }

    override fun createHighlightingPass(file: PsiFile, editor: Editor): TextEditorHighlightingPass? {
        if (file.language != BasedPythonLanguage) return null
        return ByStringMarginPass(file.project, editor)
    }
}

/** The margins currently drawn in an editor, so a pass can tell what it has to change. */
private val DRAWN: Key<List<RangeHighlighter>> = Key.create("basedpython.string.margins")

private class ByStringMarginPass(project: Project, private val editor: Editor) :
    TextEditorHighlightingPass(project, editor.document, false) {

    private var margins: List<StringMargin> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        // A snapshot, because this runs off the EDT while the document may be edited under it.
        margins = StringMargins.marginsIn(document.immutableCharSequence)
    }

    override fun doApplyInformationToEditor() {
        val markup = editor.markupModel
        val drawn = editor.getUserData(DRAWN).orEmpty()

        // Nothing moved: leave the existing highlighters be. Replacing them unconditionally would
        // repaint every literal in the file on each pass — which the daemon runs after every
        // keystroke, including keystrokes nowhere near a string.
        if (drawn.size == margins.size && drawn.zip(margins).all { (h, m) -> h.isValid && h.matches(m) }) {
            return
        }

        drawn.forEach(markup::removeHighlighter)
        editor.putUserData(
            DRAWN,
            margins.map { margin ->
                markup.addRangeHighlighter(
                    null,
                    margin.literalStart,
                    margin.literalEnd,
                    // Below everything else that draws itself: a margin is background, and it
                    // should never be what covers a caret row or a search hit.
                    HighlighterLayer.LAST,
                    HighlighterTargetArea.EXACT_RANGE,
                ).also { (it as RangeHighlighterEx).setCustomRenderer(ByStringMarginRenderer(margin)) }
            },
        )
    }

    /** Whether this highlighter already draws [margin], renderer and range both. */
    private fun RangeHighlighter.matches(margin: StringMargin): Boolean =
        startOffset == margin.literalStart &&
            endOffset == margin.literalEnd &&
            (this as? RangeHighlighterEx)?.customRenderer == ByStringMarginRenderer(margin)
}
