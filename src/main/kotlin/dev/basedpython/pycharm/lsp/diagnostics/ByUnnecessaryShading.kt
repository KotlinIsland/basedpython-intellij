package dev.basedpython.pycharm.lsp.diagnostics

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.ex.MarkupModelEx
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.impl.DocumentMarkupModel
import com.intellij.openapi.editor.impl.event.MarkupModelListener
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.basedpython.pycharm.lsp.isByServerFile
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Greys the whole of a range the editor draws as unused in a file `by` serves, the names inside it
 * included — `print(1)` under `return` is all grey, as it is in a `.py`.
 *
 * `by` marks such a range with the `Unnecessary` tag (*Code is always unreachable*, an unused
 * name), and the platform's LSP client draws it as an unused symbol: [CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES]
 * on the layer of the diagnostic's severity, which for `by`'s hints is the weak-warning one. Every
 * semantic token is drawn on that same layer (the client gives them the `TEXT_ATTRIBUTES`
 * severity, which the platform layers with weak warnings), and of two highlighters on one layer the
 * editor takes the colour of the narrower. So each name inside the range kept its token's colour
 * and only what no token covers went grey: `(` and `)` of `print(1)`, not `print` or `1` (measured
 * in PyCharm 263.7607 against `by` a110d7d8). PyCharm's own unreachable code is a warning, a layer
 * above the colours under it, and is grey throughout.
 *
 * So over each such range this lays a second highlighter with the same colours on [SHADE_LAYER],
 * one above the weak warnings: it wins over every token inside, whatever the token's colour and
 * however many ranges a line holds, and stays under warnings and errors as PyCharm's does. Only
 * the foreground of [CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES] decides anything, so the colour
 * scheme's choice of grey is the one drawn.
 *
 * The ranges are read off the document's own markup, where the platform puts the diagnostics: what
 * is drawn as unused is exactly what should be grey, whoever drew it, and the shades follow it as
 * the highlighting is redrawn. A highlighter that comes, goes or changes colour there schedules one
 * reconciliation of that editor's shades on the EDT, after the change that caused it, rather than
 * adding to the markup model from inside its own notification.
 *
 * The shades sit in each editor's own markup model and go when the editor does; [dispose] takes
 * out what is left, along with the listeners, when the plugin unloads.
 *
 * EDT only: editors are created and released there, and the markup model is changed there.
 */
@Service(Service.Level.PROJECT)
internal class ByUnnecessaryShading(private val project: Project) : Disposable {

    private val shadings = HashMap<Editor, Shading>()

    /** Starts shading [editor] if it shows a file `by` serves. */
    fun opened(editor: Editor) {
        if (project.isDisposed || editor in shadings) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        if (!file.isByServerFile()) return
        val markup = DocumentMarkupModel.forDocument(editor.document, project, true) as? MarkupModelEx ?: return
        shadings[editor] = Shading(editor, markup).also { it.reconcile() }
    }

    /** Stops shading [editor], which the platform is releasing. */
    fun released(editor: Editor) {
        shadings.remove(editor)?.close()
    }

    /** The shades drawn in [editor] now, for a test to look at. */
    internal fun shadesIn(editor: Editor): List<RangeHighlighter> = shadings[editor]?.shades?.values?.toList().orEmpty()

    override fun dispose() {
        for (shading in shadings.values) shading.close()
        shadings.clear()
    }

    /** One editor's shades, kept in step with what its document's markup draws as unused. */
    private inner class Shading(private val editor: Editor, private val markup: MarkupModelEx) : MarkupModelListener {

        /** Each unused highlighter of the document's, and the shade laid over it in [editor]. */
        val shades = IdentityHashMap<RangeHighlighter, RangeHighlighter>()

        private val listening: Disposable = Disposer.newDisposable(this@ByUnnecessaryShading, "by unnecessary shading")
        private var scheduled = false
        private var closed = false

        init {
            markup.addMarkupModelListener(listening, this)
        }

        override fun afterAdded(highlighter: RangeHighlighterEx) = touched(highlighter)

        override fun afterRemoved(highlighter: RangeHighlighterEx) = touched(highlighter)

        override fun attributesChanged(highlighter: RangeHighlighterEx, renderersChanged: Boolean, fontStyleOrColorChanged: Boolean) =
            touched(highlighter)

        /**
         * A highlighter that is drawn as unused now, or was shaded before, may change which shades
         * there should be; nothing else can. A redraw of the document's highlighting touches many in
         * one go, and they are all answered by one reconciliation.
         */
        private fun touched(highlighter: RangeHighlighter) {
            if (scheduled || closed) return
            if (highlighter !in shades && !isUnused(highlighter)) return
            scheduled = true
            ApplicationManager.getApplication().invokeLater(
                {
                    scheduled = false
                    if (!closed && !editor.isDisposed) reconcile()
                },
                ModalityState.any(),
                project.disposed,
            )
        }

        /** Makes [shades] one shade over each highlighter the document draws as unused, at its range. */
        fun reconcile() {
            val unused: MutableSet<RangeHighlighter> = Collections.newSetFromMap(IdentityHashMap())
            markup.allHighlighters.filterTo(unused) { isUnused(it) }
            val entries = shades.entries.iterator()
            while (entries.hasNext()) {
                val (original, shade) = entries.next()
                if (original in unused && shade.isValid && shade.textRange == original.textRange) continue
                editor.markupModel.removeHighlighter(shade)
                entries.remove()
            }
            for (original in unused) {
                if (original in shades) continue
                shades[original] = editor.markupModel.addRangeHighlighter(
                    CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES,
                    original.startOffset,
                    original.endOffset,
                    SHADE_LAYER,
                    HighlighterTargetArea.EXACT_RANGE,
                )
            }
        }

        fun close() {
            closed = true
            Disposer.dispose(listening)
            if (!editor.isDisposed) shades.values.forEach(editor.markupModel::removeHighlighter)
            shades.clear()
        }
    }

    /** Opens and releases the shading of each editor the platform creates. */
    class Editors : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            getInstance(project).opened(event.editor)
        }

        override fun editorReleased(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            project.getServiceIfCreated(ByUnnecessaryShading::class.java)?.released(event.editor)
        }
    }

    companion object {
        /**
         * One above the weak-warning layer that both the semantic tokens and `by`'s hint
         * diagnostics are drawn on, and below the warnings and errors whose colours should still
         * show through a grey range.
         */
        const val SHADE_LAYER: Int = HighlighterLayer.WEAK_WARNING + 1

        fun getInstance(project: Project): ByUnnecessaryShading = project.service()

        /** Whether [highlighter] is drawn as unused below the shade's layer, over some text. */
        private fun isUnused(highlighter: RangeHighlighter): Boolean =
            highlighter.isValid &&
                highlighter.layer < SHADE_LAYER &&
                highlighter.startOffset < highlighter.endOffset &&
                highlighter.textAttributesKey == CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES
    }
}
