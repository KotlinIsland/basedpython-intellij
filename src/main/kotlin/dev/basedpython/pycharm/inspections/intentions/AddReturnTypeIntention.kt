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
 * Adds `-> None` return-type annotation to a `def` statement under the caret that lacks one.
 *
 * Finds the closing `)` of the parameter list and inserts ` -> None` before the trailing `:`.
 */
class AddReturnTypeIntention : IntentionAction {

    override fun getText(): String = "Add '-> None' return type annotation"
    override fun getFamilyName(): String = "Add return type annotation"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is BasedPythonFile) return false
        return findInsertionOffset(file.text, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file !is BasedPythonFile) return
        val text = file.text
        val offset = editor.caretModel.offset
        val insertAt = findInsertionOffset(text, offset) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Add return type annotation", null, {
            editor.document.insertString(insertAt, " -> None")
        }, file)
    }

    /**
     * Returns the offset just before the `:` that ends the `def` header the caret is on,
     * only if there is no existing `->` annotation.
     */
    private fun findInsertionOffset(text: String, caretOffset: Int): Int? {
        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)

        // Collect token positions for `def` on the same logical line as the caret
        data class Tok(val type: com.intellij.psi.tree.IElementType, val start: Int, val end: Int, val value: String)

        val tokens = mutableListOf<Tok>()
        while (lexer.tokenType != null) {
            tokens += Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, text.substring(lexer.tokenStart, lexer.tokenEnd))
            lexer.advance()
        }

        // Find the `def` token whose header range contains the caret
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok.type == BasedPythonTokenTypes.KEYWORD && tok.value == "def") {
                // scan forward to find the `:` that ends this def header (depth-balanced)
                var depth = 0
                var hasArrow = false
                var colonIdx = -1
                var j = i + 1
                while (j < tokens.size) {
                    val t = tokens[j]
                    when {
                        t.type == BasedPythonTokenTypes.LPAREN -> depth++
                        t.type == BasedPythonTokenTypes.RPAREN -> depth--
                        t.type == BasedPythonTokenTypes.OPERATOR && t.value == "->" && depth == 0 -> hasArrow = true
                        t.type == BasedPythonTokenTypes.COLON && depth == 0 -> { colonIdx = j; break }
                        // newline at depth 0 without a colon means malformed; stop
                        t.type == BasedPythonTokenTypes.WHITESPACE && t.value.contains('\n') && depth == 0 -> break
                    }
                    j++
                }
                if (colonIdx >= 0) {
                    val defStart = tok.start
                    val colonStart = tokens[colonIdx].start
                    // caret must be within the def header range
                    if (caretOffset in defStart..colonStart && !hasArrow) {
                        // insert point = just before the colon token
                        return colonStart
                    }
                }
            }
            i++
        }
        return null
    }
}
