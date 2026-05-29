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
 * Converts `class X:` to `data class X:` when the caret is on the `class` keyword.
 * Only fires when there is no `data` modifier already present.
 */
class ConvertToDataClassIntention : IntentionAction {

    override fun getText(): String = "Convert to 'data class'"
    override fun getFamilyName(): String = "Convert to data class"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is BasedPythonFile) return false
        return findClassKeywordOffset(file.text, editor.caretModel.offset, expectDataClass = false) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        if (file !is BasedPythonFile) return
        val insertAt = findClassKeywordOffset(file.text, editor.caretModel.offset, expectDataClass = false) ?: return
        WriteCommandAction.runWriteCommandAction(project, "Convert to data class", null, {
            editor.document.insertString(insertAt, "data ")
        }, file)
    }

    companion object {
        /**
         * Finds the offset of the `class` keyword on the logical line containing [caretOffset].
         * If [expectDataClass] is false, returns the offset only when no `data` precedes `class`.
         * If [expectDataClass] is true, returns the offset of `data` only when `data class` is present.
         */
        fun findClassKeywordOffset(text: String, caretOffset: Int, expectDataClass: Boolean): Int? {
            val lexer = BasedPythonLexer()
            lexer.start(text, 0, text.length, 0)

            data class Tok(val type: com.intellij.psi.tree.IElementType, val start: Int, val end: Int, val value: String)
            val tokens = mutableListOf<Tok>()
            while (lexer.tokenType != null) {
                tokens += Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd, text.substring(lexer.tokenStart, lexer.tokenEnd))
                lexer.advance()
            }

            for (i in tokens.indices) {
                val tok = tokens[i]
                if (tok.type == BasedPythonTokenTypes.KEYWORD && tok.value == "class") {
                    if (caretOffset !in tok.start..tok.end) continue
                    // Look back for `data` on the same line
                    val prevNonWs = tokens.subList(0, i).lastOrNull {
                        it.type != BasedPythonTokenTypes.WHITESPACE ||
                                !it.value.contains('\n')
                    }
                    val hasData = prevNonWs?.let {
                        it.type == BasedPythonTokenTypes.KEYWORD && it.value == "data"
                    } ?: false

                    return when {
                        !expectDataClass && !hasData -> tok.start   // insert "data " before "class"
                        expectDataClass && hasData -> prevNonWs!!.start  // remove "data " starting here
                        else -> null
                    }
                }
            }
            return null
        }
    }
}
