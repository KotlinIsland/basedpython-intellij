package dev.basedpython.pycharm.inspections.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Wraps the attribute access under the caret in null-safe form:
 *   `foo.bar`  →  `foo?.bar`
 *
 * The caret must be on or adjacent to a `.` DOT token that is preceded by an identifier/expression.
 * If the `.` is already preceded by `?` (i.e. already `?.`), the intention is not available.
 */
class WrapNullSafeIntention : IntentionAction {

    override fun getText(): String = "Convert '.' to '?.' (null-safe access)"
    override fun getFamilyName(): String = "Wrap in null-safe access"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is BasedPythonFile) return false
        return findDotOffset(file.text, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file !is BasedPythonFile) return
        val dotOffset = findDotOffset(file.text, editor.caretModel.offset) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Wrap in null-safe access", null, {
            // Replace the single '.' at dotOffset with '?.'
            editor.document.replaceString(dotOffset, dotOffset + 1, "?.")
        }, file)
    }

    /**
     * Returns the document offset of the `.` DOT token nearest to [caretOffset]
     * that is NOT already part of `?.` or `...`.
     */
    private fun findDotOffset(text: String, caretOffset: Int): Int? {
        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)

        data class Tok(val type: com.intellij.psi.tree.IElementType, val start: Int, val end: Int)
        val tokens = mutableListOf<Tok>()
        while (lexer.tokenType != null) {
            tokens += Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }

        for (i in tokens.indices) {
            val tok = tokens[i]
            if (tok.type != BasedPythonTokenTypes.DOT) continue
            // Check caret is on or adjacent
            if (caretOffset < tok.start || caretOffset > tok.end) continue
            // Ensure it's a single '.' (not '...' — those come as OPERATOR from the lexer, so this is fine)
            // Make sure it's not already '?.' — look at preceding char in raw text
            if (tok.start > 0 && text[tok.start - 1] == '?') continue
            return tok.start
        }
        return null
    }
}
