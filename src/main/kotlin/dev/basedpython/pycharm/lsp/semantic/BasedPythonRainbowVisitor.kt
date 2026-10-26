package dev.basedpython.pycharm.lsp.semantic

import com.intellij.codeInsight.daemon.RainbowVisitor
import com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Rainbow highlighting for `.by`: every parameter and local of a function in a colour of its own,
 * the same colour wherever the name appears — *Settings | Editor | Color Scheme | Language Defaults
 * | Semantic highlighting*.
 *
 * Which names those are is `by`'s answer ([ByLocalNames]: its `parameter` and `variable` tokens
 * marked `functionScope`), so a global a function only reads keeps its ordinary colour, as do
 * attributes and module names. The platform runs this only where the setting is on for the file's
 * language, so with it off nothing is asked of `by`.
 *
 * Colours are chosen per file and name: a `.by` tree has no element for a function to hang a
 * per-function choice on, so the same name in two functions shares a colour.
 */
internal class BasedPythonRainbowVisitor : RainbowVisitor() {

    /** This pass's parameters and locals, by where each starts. */
    private var names: Map<Int, ByLocalName> = emptyMap()

    override fun suitableForFile(file: PsiFile): Boolean = file is BasedPythonFile

    override fun analyze(file: PsiFile, updateWholeFile: Boolean, holder: HighlightInfoHolder, action: Runnable): Boolean {
        names = ByLocalNames.getInstance(file.project).of(file).associateBy { it.range.startOffset }
        try {
            return super.analyze(file, updateWholeFile, holder, action)
        } finally {
            names = emptyMap()
        }
    }

    override fun visit(element: PsiElement) {
        if (element.node?.elementType != BasedPythonTokenTypes.IDENTIFIER) return
        val range = element.textRange
        val name = names[range.startOffset]?.takeIf { it.range == range } ?: return
        val key = if (name.isParameter) BasedPythonHighlightKeys.PARAMETER else BasedPythonHighlightKeys.LOCAL_VARIABLE
        addInfo(getInfo(element.containingFile, element, element.text, key))
    }

    override fun clone(): HighlightVisitor = BasedPythonRainbowVisitor()
}
