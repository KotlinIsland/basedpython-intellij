package dev.basedpython.pycharm.debug

import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.TokenType
import com.intellij.psi.tree.TokenSet
import com.intellij.psi.util.PsiTreeUtil
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Which lines of a basedpython file a line breakpoint can go on: the ones holding code.
 *
 * The rule is PyCharm's own for `.py` (`PyLineBreakpointType.canPutAt`), and like it, lexical: a
 * line holds code when one of its tokens is not whitespace, a comment or part of a string, and the
 * line before it does not end in a backslash, which would make it the rest of that line's
 * statement. So blank lines, comment lines, the inside of a multi-line string and a docstring are
 * refused, and a closing `)` on a line of its own is not.
 *
 * Lexical on purpose, rather than asked of `by/syntaxOutline`: the platform asks this synchronously,
 * on the EDT, as the gutter is clicked, and an outline can be a revision behind the text — exactly
 * when a line has just been typed. The tokens are the text's own, always current. Where on a line
 * with code the debugger can actually stop is the debugger's to say; this only keeps a breakpoint
 * off lines with nothing to stop at, where a debugger has to move it somewhere else.
 */
internal object ByBreakableLines {

    /** Tokens that put no code on a line. */
    private val NOT_CODE = TokenSet.create(
        TokenType.WHITE_SPACE,
        BasedPythonTokenTypes.NEWLINE,
        BasedPythonTokenTypes.COMMENT,
        BasedPythonTokenTypes.STRING,
    )

    fun holdsCode(project: Project, document: Document, line: Int): Boolean {
        if (line < 0 || line >= document.lineCount) return false
        if (line > 0 && continuesOnto(document, line - 1)) return false
        val file = PsiDocumentManager.getInstance(project).getPsiFile(document) ?: return false
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        var leaf = file.findElementAt(start)
        while (leaf != null && leaf.textRange.startOffset < end) {
            if (leaf.node.elementType !in NOT_CODE) return true
            leaf = PsiTreeUtil.nextLeaf(leaf)
        }
        return false
    }

    /** Whether [line] ends in a backslash continuation, as PyCharm reads one. */
    private fun continuesOnto(document: Document, line: Int): Boolean {
        val text = document.charsSequence.subSequence(document.getLineStartOffset(line), document.getLineEndOffset(line))
        return text.trimEnd().endsWith('\\')
    }
}
