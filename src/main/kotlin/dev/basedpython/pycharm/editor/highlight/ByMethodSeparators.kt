package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.SeparatorPlacement
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * *Settings | Editor | General | Appearance | Show method separators* in a `.by` file: a line above
 * each function or class that follows another function or class in the same suite, as PyCharm
 * draws them in a `.py` — so above the second method of a class and every one after it, above a
 * class that follows a function, and above a decorated definition's first decorator.
 *
 * Which statements those are is `by`'s outline, which a highlighting pass waits for.
 */
class ByMethodSeparators : LineMarkerProvider {

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun collectSlowLineMarkers(elements: List<PsiElement>, result: MutableCollection<in LineMarkerInfo<*>>) {
        if (!DaemonCodeAnalyzerSettings.getInstance().SHOW_METHOD_SEPARATORS) return
        val file = elements.firstOrNull()?.containingFile as? BasedPythonFile ?: return
        val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return
        val starts = separated(outline, file.viewProvider.contents).toHashSet()
        if (starts.isEmpty()) return
        val color = EditorColorsManager.getInstance().globalScheme.getColor(CodeInsightColors.METHOD_SEPARATORS_COLOR)
        for (element in elements) {
            if (element.firstChild != null || element.textRange.startOffset !in starts) continue
            // a separator only, with no icon
            result += LineMarkerInfo(element, element.textRange).apply {
                separatorColor = color
                separatorPlacement = SeparatorPlacement.TOP
            }
        }
    }

    companion object {
        /** Where each function or class that follows another in its suite starts. */
        internal fun separated(outline: ByOutline, text: CharSequence): List<Int> {
            fun isDefinition(statement: ByOutline.Statement) = statement.name != null
            val starts = mutableListOf<Int>()
            fun visit(statements: List<ByOutline.Statement>) {
                for ((i, statement) in statements.withIndex()) {
                    if (i > 0 && isDefinition(statement) && isDefinition(statements[i - 1])) starts += statement.range.startOffset
                    for (clause in statement.clauses) visit(clause.body)
                }
            }
            visit(outline.statements)
            return starts
        }
    }
}
