package dev.basedpython.pycharm.highlight.fstring

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.highlight.BasedPythonHighlightKeys
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Annotator that sub-lexes f-string literals in `.by` files and highlights every `{ ... }`
 * interpolation region distinctly from the surrounding string literal.
 *
 * The detection / range computation is delegated entirely to the pure
 * [FStringInterpolation] helper, so this class only handles the PSI plumbing: it acts on
 * STRING leaf elements, computes interpolation ranges relative to the literal, offsets them
 * by the element's start offset, and emits silent INFORMATION annotations.
 *
 * No lexer change is required — interpolation boundaries are recovered from the raw token text.
 *
 * Highlighting uses the existing [BasedPythonHighlightKeys.FSTRING_INTERP] color key (reused;
 * no new key defined).
 */
class FStringInterpolationAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Only operate on STRING leaf tokens (flat PSI: leaves carry the element type).
        val node = element.node ?: return
        if (node.elementType != BasedPythonTokenTypes.STRING) return

        val raw = element.text ?: return
        if (raw.isEmpty()) return

        val analysis = FStringInterpolation.analyze(raw)
        if (!analysis.isFString || analysis.ranges.isEmpty()) return

        val base = element.textRange.startOffset
        for (r in analysis.ranges) {
            val absolute = TextRange(base + r.startOffset, base + r.endOffset)
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(absolute)
                .textAttributes(BasedPythonHighlightKeys.FSTRING_INTERP)
                .create()
        }
    }
}
