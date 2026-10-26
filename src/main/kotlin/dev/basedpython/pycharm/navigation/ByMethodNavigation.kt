package dev.basedpython.pycharm.navigation

import com.intellij.codeInsight.navigation.MethodNavigationOffsetProvider
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Navigate | Next Method / Previous Method (Alt+↓ / Alt+↑) in a `.by` file: the names of the
 * functions at module level and the methods of every class, as PyCharm steps through a `.py` —
 * not a function nested in a function, and not a class.
 *
 * Which statement is a `def` and where its name is are `by`'s outline of the text on screen; without
 * one the caret stays where it is.
 */
class ByMethodNavigation : MethodNavigationOffsetProvider {

    override fun getMethodNavigationOffsets(file: PsiFile, caretOffset: Int): IntArray? {
        if (file !is BasedPythonFile) return null
        val document = file.viewProvider.document ?: return null
        val outline = ByOutlines.getInstance(file.project).current(document) ?: return null
        return methodNames(outline, document.immutableCharSequence).toIntArray()
    }

    companion object {
        /** Where each function at module level and each method of a class is named, in source order. */
        internal fun methodNames(outline: ByOutline, text: CharSequence): List<Int> {
            val offsets = mutableListOf<Int>()
            fun visit(statements: List<ByOutline.Statement>, inClass: Boolean, atTop: Boolean) {
                for (statement in statements) {
                    val name = statement.name
                    val def = name != null && statement.clauses.firstOrNull()?.keyword?.let { text.subSequence(it.startOffset, it.endOffset) } == "def"
                    if (def && (atTop || inClass)) offsets += name!!.startOffset
                    val isClass = name != null && !def
                    for (clause in statement.clauses) {
                        // a function's own body holds no methods; a class's does, and so does a block at its level
                        visit(clause.body, inClass = isClass || (inClass && !def), atTop = atTop && !def && !isClass)
                    }
                }
            }
            visit(outline.statements, inClass = false, atTop = true)
            return offsets
        }
    }
}
