package dev.basedpython.pycharm.editor.scope

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.markup.DefaultLineMarkerRenderer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.LineMarkerRendererEx
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lsp.isByServerFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlineListener
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * The lines of the block the caret is in, from the line its header's `:` is on to its last line —
 * what *Settings | Editor | General | Highlight on Caret Movement | Current scope* marks, for a
 * `.by` file.
 *
 * The platform finds that block only through structural brace tokens
 * (`BraceHighlightingHandler.highlightScope`), and a `.by` block has none, so for `.by` it marks
 * nothing. The block here is `by`'s ([ByOutline]), chosen as the platform chooses a braced one,
 * measured on Kotlin in IntelliJ IDEA 263.7307.44:
 * - the caret anywhere in a body, a blank line of it or the end of its last line included, is in
 *   that block, and of nested ones the innermost;
 * - the caret on a header before its `:`, the parentheses of a `for (…)`, is in the block around it;
 * - a block on one line has no lines to mark.
 *
 * Each clause is a block of its own, as each `{ … }` of an `if … else` is; a `match` is one block
 * from its `:` to its last `case`, as a `when { … }` is.
 */
object ByScopes {

    /** The lines to mark for [offset] of [text], first and last, or null when there are none. */
    fun linesAt(outline: ByOutline, text: CharSequence, offset: Int): IntRange? {
        var best: Pair<Int, Int>? = null // the colon's start, the block's end
        fun visit(statements: List<ByOutline.Statement>) {
            for (statement in statements) {
                for ((index, clause) in statement.clauses.withIndex()) {
                    val colon = clause.colon
                    if (colon != null) {
                        val matchHead = index == 0 && clause.body.isEmpty() && statement.clauses.size > 1
                        val end = if (matchHead) statement.range.endOffset else clause.range.endOffset
                        if (offset in colon.endOffset..end) {
                            val current = best
                            if (current == null || end - colon.startOffset <= current.second - current.first) {
                                best = colon.startOffset to end
                            }
                        }
                    }
                    visit(clause.body)
                }
            }
        }
        visit(outline.statements)
        val (from, to) = best ?: return null
        val first = lineOf(text, from)
        val last = lineOf(text, to)
        return if (last > first) first..last else null
    }

    private fun lineOf(text: CharSequence, offset: Int): Int = (0 until offset).count { text[it] == '\n' }
}

/**
 * Draws [ByScopes]' lines as the platform draws a braced language's current scope: one
 * `LINES_IN_RANGE` highlighter on layer 0, with a 1px line in the matched-brace colour on the
 * gutter's right edge, against the text. Only while *Current scope* is on, which is the platform's
 * own setting, in editors of files `by` serves, from the outline already held for the text on
 * screen: a caret move asks `by` nothing.
 *
 * Updated on every caret move, at once, as the platform updates its brace and scope marks (19 ms
 * after the move in Kotlin, with no delay of its own); and again when an outline arrives for the
 * file, since a mark drawn while typing is drawn from the outline of the text before the keystroke,
 * or left as it was when there is none yet. Like the platform's, the mark is taken out of an editor
 * that stops being the selected one, and is left alone while there is a selection.
 *
 * The highlighters are in each editor's own markup and go with it; [dispose], which runs as the
 * plugin unloads, takes out the ones still on screen, and the listeners go with this service.
 *
 * EDT only.
 */
@Service(Service.Level.PROJECT)
internal class ByCurrentScope(private val project: Project) : Disposable {

    private val marks = HashMap<Editor, RangeHighlighter>()

    init {
        EditorFactory.getInstance().eventMulticaster.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) = update(event.editor)
            },
            this,
        )
        val connection = project.messageBus.connect(this)
        connection.subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    (event.oldEditor as? TextEditor)?.editor?.let(::clear)
                }
            },
        )
        connection.subscribe(
            ByOutlineListener.TOPIC,
            ByOutlineListener { file ->
                ApplicationManager.getApplication().invokeLater({ redraw(file) }, ModalityState.any(), project.disposed)
            },
        )
    }

    /** Marks the block the caret of [editor] is in, or takes the mark out when there is none. */
    fun update(editor: Editor) {
        if (editor.isDisposed || editor.project != project) return
        if (!CodeInsightSettings.getInstance().HIGHLIGHT_SCOPE) return clear(editor)
        if (editor.selectionModel.hasSelection()) return
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document) ?: return
        if (!file.isByServerFile()) return
        val outline = ByOutlines.getInstance(project).current(document) ?: return
        val lines = ByScopes.linesAt(outline, document.immutableCharSequence, editor.caretModel.offset)
            ?: return clear(editor)
        val start = document.getLineStartOffset(lines.first)
        val end = document.getLineEndOffset(lines.last)
        val old = marks[editor]
        if (old != null && old.isValid && old.startOffset == start && old.endOffset == end) return
        clear(editor)
        marks[editor] = editor.markupModel.addRangeHighlighter(null, start, end, 0, HighlighterTargetArea.LINES_IN_RANGE).also {
            it.lineMarkerRenderer = DefaultLineMarkerRenderer(CodeInsightColors.MATCHED_BRACE_ATTRIBUTES, 1, 0, LineMarkerRendererEx.Position.RIGHT)
        }
    }

    /** The mark in [editor], for a test to look at. */
    internal fun markIn(editor: Editor): RangeHighlighter? = marks[editor]?.takeIf { it.isValid }

    private fun redraw(file: VirtualFile) {
        for (editor in marks.keys.toList() + EditorFactory.getInstance().allEditors) {
            if (FileDocumentManager.getInstance().getFile(editor.document) == file) update(editor)
        }
    }

    private fun clear(editor: Editor) {
        val mark = marks.remove(editor) ?: return
        if (!editor.isDisposed) editor.markupModel.removeHighlighter(mark)
    }

    override fun dispose() {
        for (editor in marks.keys.toList()) clear(editor)
    }

    /** Starts the service for the first editor of a file `by` serves, and forgets released ones. */
    class Editors : EditorFactoryListener {
        override fun editorCreated(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            val file = FileDocumentManager.getInstance().getFile(event.editor.document) ?: return
            if (file.isByServerFile()) getInstance(project)
        }

        override fun editorReleased(event: EditorFactoryEvent) {
            val project = event.editor.project ?: return
            project.getServiceIfCreated(ByCurrentScope::class.java)?.marks?.remove(event.editor)
        }
    }

    companion object {
        fun getInstance(project: Project): ByCurrentScope = project.service()
    }
}
