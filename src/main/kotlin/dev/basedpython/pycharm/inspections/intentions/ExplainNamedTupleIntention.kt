package dev.basedpython.pycharm.inspections.intentions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Informational intention that recognises an anonymous named-tuple literal of the form
 * `(name: str, age: int)` under the caret and displays an explanatory message.
 *
 * Detection heuristic: a LPAREN followed by IDENTIFIER COLON, meaning the first element
 * of the paren-group is typed. No write action is performed.
 */
class ExplainNamedTupleIntention : IntentionAction {

    override fun getText(): String = "Explain anonymous named-tuple syntax"
    override fun getFamilyName(): String = "Explain anonymous named-tuple"
    override fun startInWriteAction(): Boolean = false

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        if (file !is BasedPythonFile) return false
        return findNamedTupleLParen(file.text, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        Messages.showInfoMessage(
            project,
            """
            BasedPython anonymous named-tuple syntax:

              (name: str, age: int)

            This creates an anonymous NamedTuple type inline, equivalent to:

              class _T(NamedTuple):
                  name: str
                  age: int

            and can be used as a return type, variable annotation, or in collections.
            The type is structural — compatible with any NamedTuple with matching fields.
            """.trimIndent(),
            "BasedPython: Anonymous Named-Tuple"
        )
    }

    /**
     * Returns the offset of the opening `(` if the token under/near [caretOffset]
     * appears to be inside an anonymous named-tuple literal.
     */
    private fun findNamedTupleLParen(text: String, caretOffset: Int): Int? {
        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)

        data class Tok(val type: com.intellij.psi.tree.IElementType, val start: Int, val end: Int)
        val tokens = mutableListOf<Tok>()
        while (lexer.tokenType != null) {
            tokens += Tok(lexer.tokenType!!, lexer.tokenStart, lexer.tokenEnd)
            lexer.advance()
        }

        // find the innermost LPAREN that contains caretOffset
        for (i in tokens.indices) {
            val tok = tokens[i]
            if (tok.type != BasedPythonTokenTypes.LPAREN) continue
            if (tok.start > caretOffset) break

            // look for matching RPAREN
            var depth = 1
            var j = i + 1
            var rparenIdx = -1
            while (j < tokens.size && depth > 0) {
                when (tokens[j].type) {
                    BasedPythonTokenTypes.LPAREN -> depth++
                    BasedPythonTokenTypes.RPAREN -> {
                        depth--
                        if (depth == 0) rparenIdx = j
                    }
                }
                j++
            }
            if (rparenIdx < 0) continue
            if (caretOffset > tokens[rparenIdx].end) continue

            // Check if first non-whitespace token after LPAREN is IDENTIFIER followed by COLON
            val nonWs = tokens.subList(i + 1, rparenIdx).filter {
                it.type != BasedPythonTokenTypes.WHITESPACE
            }
            if (nonWs.size >= 2 &&
                nonWs[0].type == BasedPythonTokenTypes.IDENTIFIER &&
                nonWs[1].type == BasedPythonTokenTypes.COLON
            ) {
                return tok.start
            }
        }
        return null
    }
}
