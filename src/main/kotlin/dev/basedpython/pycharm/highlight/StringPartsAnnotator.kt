package dev.basedpython.pycharm.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Colours what is inside a string literal: its escape sequences, and an f-string's or t-string's
 * `{...}` interpolations.
 *
 * Semantic tokens cannot say it. A token covers a whole literal, and an interpolation is a span with
 * tokens of its own inside it, which tokens are not allowed to overlap. So the parts come from `by`'s
 * syntax outline ([ByOutlines]), which reads them off the parse: which prefix makes a literal raw,
 * which `\N{...}` is an escape in a `str` and not in `bytes`, where a nested quote or a `{{` leaves
 * an interpolation. The plugin's own lexer decides none of it; it only says which leaves are
 * strings, and every part inside one of them is coloured.
 *
 * Runs in the daemon's background pass, where waiting on the server is what the pass is for.
 */
class StringPartsAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        if (element.node?.elementType != BasedPythonTokenTypes.STRING) return
        val file = element.containingFile as? BasedPythonFile ?: return
        val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return

        val leaf = element.textRange
        for (part in outline.stringsIn(leaf)) {
            for (interpolation in part.interpolations) {
                annotate(holder, leaf, interpolation, BasedPythonHighlightKeys.FSTRING_INTERP)
            }
            for (escape in part.escapes) {
                annotate(holder, leaf, escape, BasedPythonHighlightKeys.STRING_ESCAPE)
            }
        }
    }

    private fun annotate(
        holder: AnnotationHolder,
        leaf: TextRange,
        range: TextRange,
        key: TextAttributesKey,
    ) {
        // An annotation must lie inside the element it is made for. The plugin's lexer and `by`'s
        // parser can disagree about where a literal ends — a nested quote in a 3.12 f-string — so
        // each string leaf colours the part of an escape or interpolation that falls inside it.
        val inside = range.intersection(leaf)?.takeUnless { it.isEmpty } ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(inside)
            .textAttributes(key)
            .create()
    }
}
