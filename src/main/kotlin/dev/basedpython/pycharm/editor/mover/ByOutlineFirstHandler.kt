package dev.basedpython.pycharm.editor.mover

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.readAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actionSystem.EditorActionHandler
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.psi.PsiDocumentManager
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.byServerFor
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * Move Statement Up/Down and Move Element Left/Right in a `.by` file, once `by`'s outline of the
 * text on screen is in.
 *
 * Both movers read the outline ([ByOutlines]) inside the move's write action, where nothing may
 * wait for the server, and an outline is a revision behind from an edit until `by` answers for it.
 * A move made in that window — the next keystroke after typing — found none: Move Element did
 * nothing at all, and Move Statement fell back to the platform's line mover. So before the move
 * starts, while waiting is still allowed, this waits for the outline (`by/syntaxOutline`, the
 * server's cheapest request, bounded by [ByOutlines]' own timeout) under a modal progress, and then
 * hands over to the platform's handler. Without a `by` to ask, it hands over at once.
 */
class ByOutlineFirstHandler(private val original: EditorActionHandler) : EditorActionHandler() {

    /**
     * Enabled while there is an outline to wait for: the platform's own answer is read off the
     * movers, which have none yet, and the Move Element one is disabled then — so the keystroke
     * would be dropped before [doExecute] could wait.
     */
    override fun isEnabledForCaret(editor: Editor, caret: Caret, dataContext: DataContext?): Boolean =
        awaiting(editor) != null || original.isEnabled(editor, caret, dataContext)

    override fun doExecute(editor: Editor, caret: Caret?, dataContext: DataContext?) {
        awaiting(editor)?.let { file ->
            val project = file.project
            PsiDocumentManager.getInstance(project).commitDocument(editor.document)
            runWithModalProgressBlocking(project, BasedPythonBundle.message("editor.move.awaitingOutline")) {
                readAction { ByOutlines.getInstance(project).forFile(file) }
            }
        }
        if (original.isEnabled(editor, caret, dataContext)) original.execute(editor, caret, dataContext)
    }

    /** [editor]'s `.by` file, when `by` is running for it and has not outlined the text on screen yet. */
    private fun awaiting(editor: Editor): BasedPythonFile? {
        val project = editor.project ?: return null
        val document = editor.document
        val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document) as? BasedPythonFile ?: return null
        val virtualFile = FileDocumentManager.getInstance().getFile(document) ?: return null
        if (byServerFor(project, virtualFile) == null) return null
        return file.takeIf { ByOutlines.getInstance(project).current(document) == null }
    }
}
