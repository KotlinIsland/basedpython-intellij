package dev.basedpython.pycharm.debug

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.util.Disposer
import com.intellij.util.concurrency.ThreadingAssertions

/**
 * What a debugger pass drew in each editor, and the one way to take it down.
 *
 * Held by a plugin service rather than in the editor's user data, and removed when that service is
 * disposed, because what is drawn carries plugin classes — a `CustomHighlighterRenderer` of ours on
 * each highlighter in the editor's markup model. Left behind in an editor that outlives the plugin,
 * they pin its class loader and the plugin cannot be unloaded.
 *
 * An editor that is released drops out of here at once. A weak map would not be enough: each
 * highlighter holds the editor's markup model, which holds the editor, so the entry's value would
 * keep its own key alive until the service went.
 *
 * EDT only, as the markup model is.
 */
internal class ByEditorMarks(parent: Disposable) : Disposable {

    private val drawn = HashMap<Editor, List<RangeHighlighter>>()

    init {
        Disposer.register(parent, this)
        EditorFactory.getInstance().addEditorFactoryListener(
            object : EditorFactoryListener {
                override fun editorReleased(event: EditorFactoryEvent) {
                    drawn.remove(event.editor)
                }
            },
            this,
        )
    }

    /** Remove everything drawn in [editor], and forget it. Nothing drawn costs nothing. */
    fun clear(editor: Editor) {
        ThreadingAssertions.assertEventDispatchThread()
        val highlighters = drawn.remove(editor) ?: return
        remove(editor, highlighters)
    }

    /** Every editor something is drawn in, for a caller deciding which of them to [clear]. */
    fun editors(): List<Editor> {
        ThreadingAssertions.assertEventDispatchThread()
        return drawn.keys.toList()
    }

    /** Remember what a pass just drew in [editor], taking down what it drew there before. */
    fun replace(editor: Editor, highlighters: List<RangeHighlighter>) {
        clear(editor)
        if (highlighters.isNotEmpty()) drawn[editor] = highlighters
    }

    override fun dispose() {
        ThreadingAssertions.assertEventDispatchThread()
        val all = drawn.toMap()
        drawn.clear()
        all.forEach(::remove)
    }

    private fun remove(editor: Editor, highlighters: List<RangeHighlighter>) {
        if (editor.isDisposed) return
        val markup = editor.markupModel
        for (highlighter in highlighters) if (highlighter.isValid) markup.removeHighlighter(highlighter)
    }
}
