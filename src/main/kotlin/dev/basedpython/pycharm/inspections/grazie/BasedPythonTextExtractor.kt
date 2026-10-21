package dev.basedpython.pycharm.inspections.grazie

import com.intellij.grazie.text.TextContent
import com.intellij.grazie.text.TextContent.TextDomain
import com.intellij.grazie.text.TextContentBuilder
import com.intellij.grazie.text.TextExtractor
import com.intellij.grazie.utils.getNotSoDistantSimilarSiblings
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.docs.render.ByDocstringSpans
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonStringLiteral
import dev.basedpython.pycharm.lang.StringLiteralDecoding

/**
 * The prose in a `.by` file, for grammar checking: its comments and its docstrings.
 *
 * **Comments** are lexical, and read off the tokens: a run of `#` lines with nothing between them
 * but single line breaks is one text, as it is one thought, with each line's `#` and indentation
 * taken off. A `#!` line is not prose.
 *
 * **Docstrings** are not lexical. A docstring is a string in a particular place — first in a
 * module, class or function body, or under an annotated field — and which places count is the
 * language's rule. So which literal is one is `by`'s answer, the same one the rendered-docs view
 * draws from ([ByDocstringSpans]: `by`'s semantic tokens mark them with the `documentation`
 * modifier). Only then is the literal read, from inside its quotes, with its indentation taken off
 * and each escape — as [StringLiteralDecoding] reads one — left as something the checker does not
 * guess at. With no `by` to ask there are
 * no docstrings here, and the comments are still checked.
 *
 * Other string literals are not extracted: most hold identifiers, keys and formats rather than
 * sentences.
 *
 * The texts are cached by the grammar checker against [ByTextContentModificationTrackerProvider]'s
 * tracker, which moves when `by`'s answer arrives after a check had to do without it.
 */
internal class BasedPythonTextExtractor : TextExtractor() {

    override fun buildTextContents(root: PsiElement, allowedDomains: Set<TextDomain>): List<TextContent> {
        if (root.containingFile !is BasedPythonFile) return emptyList()
        if (TextDomain.COMMENTS in allowedDomains && isProse(root)) {
            val lines = getNotSoDistantSimilarSiblings(root, ::isProse)
            val text = TextContent.joinWithWhitespace('\n', lines.mapNotNull { COMMENT.build(it, TextDomain.COMMENTS) })
            return listOfNotNull(text)
        }
        if (TextDomain.DOCUMENTATION in allowedDomains && root is BasedPythonStringLiteral && isDocstring(root)) {
            return listOfNotNull(docstring(root))
        }
        return emptyList()
    }

    private fun isProse(element: PsiElement): Boolean =
        element is PsiComment && !(element.textRange.startOffset == 0 && element.text.startsWith("#!"))

    private fun isDocstring(literal: BasedPythonStringLiteral): Boolean {
        val range = literal.textRange
        return ByDocstringSpans.of(literal.containingFile).any { it.range.contains(range) }
    }

    /** The docstring's text: the content between the quotes, its indentation and escapes left out. */
    private fun docstring(literal: BasedPythonStringLiteral): TextContent? {
        val content = DOCSTRING.build(literal, TextDomain.DOCUMENTATION) ?: return null
        val shape = literal.shape ?: return content
        val escapes = escapesIn(literal.text.substring(shape.contentStart, shape.contentEnd), shape.isRaw)
            .mapNotNull { content.fileRangeToText(it.shiftRight(literal.textRange.startOffset + shape.contentStart)) }
            .map(TextContent.Exclusion::markUnknown)
        return if (escapes.isEmpty()) content else content.excludeRanges(escapes)
    }

    /**
     * The escape sequences of [body], a literal's content, as ranges of it: every character
     * [StringLiteralDecoding] decodes from more than one character of the source.
     */
    private fun escapesIn(body: String, isRaw: Boolean): List<TextRange> {
        if (isRaw) return emptyList()
        val offsets = IntArray(body.length + 1)
        val decoded = StringBuilder()
        StringLiteralDecoding.decode(body, isRaw = false, out = decoded, sourceOffsets = offsets)
        return (0 until decoded.length)
            .filter { offsets[it + 1] - offsets[it] > 1 }
            .map { TextRange(offsets[it], offsets[it + 1]) }
    }

    private companion object {
        val COMMENT: TextContentBuilder = TextContentBuilder.FromPsi.removingIndents(" \t#").removingLineSuffixes(" \t")
        val DOCSTRING: TextContentBuilder = TextContentBuilder.FromPsi.removingIndents(" \t").removingLineSuffixes(" \t")
    }
}
