package dev.basedpython.pycharm.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Colours escape sequences inside string literals.
 *
 * This is what is left of the old whole-file semantic annotator. Everything it used to guess —
 * builtins, `self`/`cls`, decorators, declaration names, parameters, type names, keyword arguments,
 * soft keywords used as identifiers — is a question about what the code *means*, which the `by`
 * server answers from real type information and reports as semantic tokens. Guessing it a second
 * time from the token stream produced a worse answer that also had to be kept in step with a
 * language that keeps moving.
 *
 * Escapes are different: a semantic token covers a whole string literal, so nothing inside the
 * quotes is ever reported. The same goes for f-string interpolation, handled by
 * [dev.basedpython.pycharm.highlight.fstring.FStringInterpolationAnnotator].
 */
class StringEscapeAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val node = element.node ?: return
        if (node.elementType != BasedPythonTokenTypes.STRING) return

        val raw = element.text ?: return
        if (raw.isEmpty()) return

        val base = element.textRange.startOffset
        for (range in stringEscapeRanges(raw)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(base + range.startOffset, base + range.endOffset))
                .textAttributes(BasedPythonHighlightKeys.STRING_ESCAPE)
                .create()
        }
    }
}
