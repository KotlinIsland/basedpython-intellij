package dev.basedpython.pycharm.editor.indent

import com.intellij.application.options.CodeStyle
import com.intellij.lang.Language
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.codeStyle.lineIndent.LineIndentProvider
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.outline.ByOutlines
import java.lang.ref.WeakReference

/**
 * The indentation of a `.by` line, for the platform's no-commit callers: Enter above all, which
 * asks once it has broken the line.
 *
 * The answer is [ByIndents], over `by`'s outline of the text — and that outline describes the text
 * before the line break, not after. So Enter's own line is answered from what
 * [dev.basedpython.pycharm.editor.smart.BasedPythonEnterHandler] worked out before breaking it
 * ([expect]), and any other line from the outline when it is for the text as it is now. Otherwise
 * there is no answer, and the platform keeps what it has.
 */
class BasedPythonLineIndentProvider : LineIndentProvider {

    override fun getLineIndent(project: Project, editor: Editor, language: Language?, offset: Int): String? {
        val document = editor.document
        expected?.takeIf { it.document.get() === document && it.line == document.getLineNumber(offset) }?.let {
            expected = null
            return it.indent
        }
        val file = PsiDocumentManager.getInstance(project).getCachedPsiFile(document) as? BasedPythonFile ?: return null
        val outline = ByOutlines.getInstance(project).current(document) ?: return null
        val indents = ByIndents(outline, document.immutableCharSequence, CodeStyle.getIndentOptions(file))
        return indents.lineIndent(document.getLineStartOffset(document.getLineNumber(offset)))?.let(indents::whitespace)
    }

    override fun isSuitableFor(language: Language?): Boolean = language == BasedPythonLanguage

    private class Expected(document: Document, val line: Int, val indent: String) {
        val document = WeakReference(document)
    }

    companion object {
        /** The answer for the line Enter is about to make; EDT only, like Enter. */
        private var expected: Expected? = null

        /** The next ask about [line] of [document] — the one Enter makes — is answered [indent]. */
        internal fun expect(document: Document, line: Int, indent: String) {
            expected = Expected(document, line, indent)
        }

        internal fun forget() {
            expected = null
        }
    }
}
