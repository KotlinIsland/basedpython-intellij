package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.editorActions.CodeBlockProvider
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Navigate | Move Caret to Code Block Start / End (Ctrl+[ / Ctrl+]) in a `.by` file: the start or
 * end of the compound statement around the caret — a decorated definition from its first decorator
 * — and pressed again there, of the one around that, as PyCharm moves in a `.py`.
 *
 * The statements are `by`'s outline of the text on screen; without one the caret stays where it is.
 */
class ByCodeBlockProvider : CodeBlockProvider {

    override fun getCodeBlockRange(editor: Editor, psiFile: PsiFile): TextRange? {
        val outline = ByOutlines.getInstance(psiFile.project).current(editor.document) ?: return null
        return blockAround(outline, editor.caretModel.offset)
    }

    companion object {
        /**
         * The innermost compound statement with [offset] strictly inside it: at a statement's start
         * or end, the one around it, so a second press moves on outwards.
         */
        internal fun blockAround(outline: ByOutline, offset: Int): TextRange? {
            var found: TextRange? = null
            outline.walk { statement, _ ->
                val range = statement.range
                if (statement.clauses.isNotEmpty() && range.startOffset < offset && offset < range.endOffset) found = range
            }
            return found
        }
    }
}
