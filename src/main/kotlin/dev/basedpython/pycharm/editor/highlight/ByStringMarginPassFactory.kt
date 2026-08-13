package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
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

/** Set once an editor has the listener below. */
private val REPAINTS: Key<Boolean> = Key.create("basedpython.string.margins.repaints")

/**
 * Makes an edit inside a marked literal repaint the whole literal.
 *
 * Editing one line repaints that line, which is all the editor can know to do — but a trim margin
 * is a rule down several lines, and moving the least-indented line moves all of it. Without this,
 * the edited line redraws at the new column and the lines above it keep the pixels of the old one
 * until something else happens to repaint them.
 *
 * The narrowest fix that works: only the literal being edited, and only when it is one that is
 * marked. An edit anywhere else changes nothing the rule is measured from, or moves whole lines,
 * which the editor already repaints for itself.
 */
private fun installRepainter(editor: Editor) {
    if (editor !is EditorEx || editor.getUserData(REPAINTS) == true) return
    editor.putUserData(REPAINTS, true)
    val listener = object : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            for (highlighter in editor.getUserData(DRAWN).orEmpty()) {
                if (highlighter.isValid &&
                    event.offset >= highlighter.startOffset &&
                    event.offset <= highlighter.endOffset
                ) {
                    editor.repaint(highlighter.startOffset, highlighter.endOffset)
                }
            }
        }
    }
    // The document outlives the editor and this listener holds one, so it goes when the editor
    // does — otherwise every editor ever opened on the file stays reachable from its document.
    val lifetime = Disposer.newDisposable("basedpython string margins")
    EditorUtil.disposeWithEditor(editor, lifetime)
    editor.document.addDocumentListener(listener, lifetime)
}

private class ByStringMarginPass(project: Project, private val editor: Editor) :
    TextEditorHighlightingPass(project, editor.document, false) {

    private var margins: List<StringMargin> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        // A snapshot, because this runs off the EDT while the document may be edited under it.
        margins = StringMargins.marginsIn(document.immutableCharSequence)
    }

    /**
     * What the pass leaves behind is a highlighter over each literal that has a margin — where
     * the rule goes *within* that literal is measured at paint time, from the highlighter's own
     * range, by [ByStringMarginRenderer]. So the only thing to reconcile here is which literals
     * are marked, and an edit inside one that the document has already moved needs no work at all.
     */
    override fun doApplyInformationToEditor() {
        val markup = editor.markupModel
        val drawn = editor.getUserData(DRAWN).orEmpty()
        installRepainter(editor)

        // The same literals, still where they were: leave the highlighters be. Replacing them
        // unconditionally would repaint every string in the file on each pass — which the daemon
        // runs after every keystroke, including keystrokes nowhere near a string.
        if (drawn.size == margins.size && drawn.zip(margins).all { (h, m) -> h.covers(m) }) return

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
                ).also { (it as RangeHighlighterEx).setCustomRenderer(ByStringMarginRenderer) }
            },
        )
    }

    /** Whether this highlighter is already the one marking [margin]'s literal. */
    private fun RangeHighlighter.covers(margin: StringMargin): Boolean =
        isValid && startOffset == margin.literalStart && endOffset == margin.literalEnd
}
