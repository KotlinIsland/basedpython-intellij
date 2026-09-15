package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeHighlighting.TextEditorHighlightingPass
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactory
import com.intellij.codeHighlighting.TextEditorHighlightingPassFactoryRegistrar
import com.intellij.codeHighlighting.TextEditorHighlightingPassRegistrar
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Keeps every open `.by` editor's trim margins up to date, as a daemon pass.
 *
 * A highlighting pass, not an annotator: an annotation is text attributes over a range, and the
 * margin is a line drawn where there may be no text (see [ByStringMarginRenderer]). This is the
 * same shape the platform gives its own indent guides — compute off the EDT, reconcile markup on
 * it — and it inherits the daemon's cancellation and its restart-on-edit for free. What it leaves in
 * an editor is owned by [ByStringMarginEditors], so that the plugin can take it back.
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
        return ByStringMarginPass(file, editor)
    }
}

/**
 * The margins drawn in each `.by` editor, and the one listener that keeps them repainted.
 *
 * Everything the margins leave in the platform's hands is owned here, because the platform does not
 * give it back when the plugin unloads: a highlighter carrying [ByStringMarginRenderer] sits in the
 * editor's markup model, and a listener or a value in the editor's user data is reachable from the
 * editor, for as long as that editor stays open. Any one of them keeps this plugin's classloader
 * alive. A project service is disposed with the plugin, and [dispose] takes all of it back out of
 * every editor that is still open.
 *
 * Keyed by [Editor], but not weakly: a highlighter refers to its markup model, which refers to its
 * editor, so a weak key would be held strongly by its own value and never cleared. An entry goes
 * when its editor is released instead, which the platform says for every editor it creates.
 *
 * EDT only. A pass applies its markup there, edits arrive there, and editors are released there.
 */
@Service(Service.Level.PROJECT)
internal class ByStringMarginEditors : Disposable {

    private val drawn = HashMap<Editor, List<RangeHighlighter>>()

    init {
        val editors = EditorFactory.getInstance()
        editors.addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    drawn.remove(event.editor)
                }
            },
            this,
        )
        editors.eventMulticaster.addDocumentListener(Repainter(), this)
    }

    /** The margin highlighters this service put in [editor], in document order. */
    fun drawnIn(editor: Editor): List<RangeHighlighter> = drawn[editor].orEmpty()

    /**
     * Replaces what is drawn in [editor] with a highlighter over each of [margins]' literals, each
     * with a renderer carrying how much `by` strips from it.
     */
    fun redraw(editor: Editor, margins: List<ByOutline.StringPart>) {
        val markup = editor.markupModel
        drawn.remove(editor)?.forEach(markup::removeHighlighter)
        if (margins.isEmpty()) return
        drawn[editor] = margins.map { margin ->
            markup.addRangeHighlighter(
                null,
                margin.range.startOffset,
                margin.range.endOffset,
                // Below everything else that draws itself: a margin is background, and it
                // should never be what covers a caret row or a search hit.
                HighlighterLayer.LAST,
                HighlighterTargetArea.EXACT_RANGE,
            ).also { (it as RangeHighlighterEx).setCustomRenderer(ByStringMarginRenderer(margin.strippedIndent ?: 0)) }
        }
    }

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
     *
     * One listener for every editor, on the multicaster, rather than one on each document: it goes
     * with this service, and there is nothing to register per editor that could be left behind.
     */
    private inner class Repainter : DocumentListener {
        override fun documentChanged(event: DocumentEvent) {
            for ((editor, highlighters) in drawn) {
                if (editor.document !== event.document || editor !is EditorEx) continue
                for (highlighter in highlighters) {
                    if (highlighter.isValid &&
                        event.offset >= highlighter.startOffset &&
                        event.offset <= highlighter.endOffset
                    ) {
                        editor.repaint(highlighter.startOffset, highlighter.endOffset)
                    }
                }
            }
        }
    }

    override fun dispose() {
        for ((editor, highlighters) in drawn) {
            if (editor.isDisposed) continue
            highlighters.forEach(editor.markupModel::removeHighlighter)
        }
        drawn.clear()
    }
}

private class ByStringMarginPass(private val file: PsiFile, private val editor: Editor) :
    TextEditorHighlightingPass(file.project, editor.document, false) {

    private var margins: List<ByOutline.StringPart> = emptyList()

    override fun doCollectInformation(progress: ProgressIndicator) {
        // `by`'s answer for this revision, asked for and waited on here, off the EDT. No answer is
        // no margins: what is stripped is the transpiler's call, and nothing here guesses at it.
        val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return
        margins = StringMargins.strippedIn(outline)
    }

    /**
     * What the pass leaves behind is a highlighter over each literal that has a margin — where
     * the rule goes *within* that literal is measured at paint time, from the highlighter's own
     * range, by [ByStringMarginRenderer]. So the only thing to reconcile here is which literals
     * are marked, and an edit inside one that the document has already moved needs no work at all.
     */
    override fun doApplyInformationToEditor() {
        val editors = myProject.service<ByStringMarginEditors>()
        val drawn = editors.drawnIn(editor)

        // The same literals, still where they were: leave the highlighters be. Replacing them
        // unconditionally would repaint every string in the file on each pass — which the daemon
        // runs after every keystroke, including keystrokes nowhere near a string.
        if (drawn.size == margins.size && drawn.zip(margins).all { (h, m) -> h.covers(m) }) return

        editors.redraw(editor, margins)
    }

    /** Whether this highlighter is already the one marking [margin]'s literal, at its indent. */
    private fun RangeHighlighter.covers(margin: ByOutline.StringPart): Boolean =
        isValid &&
            startOffset == margin.range.startOffset &&
            endOffset == margin.range.endOffset &&
            ((this as? RangeHighlighterEx)?.customRenderer as? ByStringMarginRenderer)?.indent == margin.strippedIndent
}
