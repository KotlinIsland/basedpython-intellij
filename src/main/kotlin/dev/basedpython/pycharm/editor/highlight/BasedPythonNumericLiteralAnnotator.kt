package dev.basedpython.pycharm.editor.highlight

import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonLexer
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes

/**
 * Distinct coloring for "interesting" numeric literals in BasedPython (.by) files:
 *   - separators with underscores  (e.g. `1_000_000`, `0xFF_FF`)
 *   - hex / octal / binary radix   (`0x1A`, `0o17`, `0b1010`)
 *   - complex literals             (`3j`, `2.5J`)
 *
 * Plain decimal integers / floats (`42`, `3.14`) are left to the lexer-based
 * [dev.basedpython.pycharm.lang.BasedPythonColors.NUMBER] coloring.
 *
 * Like the existing [dev.basedpython.pycharm.highlight.BasedPythonAnnotator] this is a
 * flat-PSI plugin: we run once on the file root and lex the full text, applying silent
 * annotations to the sub-ranges of NUMBER tokens that qualify.
 */
class BasedPythonNumericLiteralAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        // Run once per file, on the BasedPython file root only.
        val file = element as? PsiFile ?: return
        if (file !is BasedPythonFile) return
        val text = file.text ?: return
        if (text.isEmpty()) return

        val baseOffset = element.textRange.startOffset

        val lexer = BasedPythonLexer()
        lexer.start(text, 0, text.length, 0)
        var t = lexer.tokenType
        while (t != null) {
            if (t == BasedPythonTokenTypes.NUMBER) {
                val start = lexer.tokenStart
                val end = lexer.tokenEnd
                if (end > start && end <= text.length) {
                    val tokText = text.substring(start, end)
                    if (isInteresting(tokText)) {
                        highlight(holder, baseOffset, start, end)
                    }
                }
            }
            lexer.advance()
            t = lexer.tokenType
        }
    }

    /**
     * Returns true when the numeric literal contains a digit-group underscore,
     * a radix prefix (0x/0o/0b), or a complex (imaginary) suffix.
     */
    private fun isInteresting(raw: String): Boolean {
        if (raw.isEmpty()) return false

        // Underscore digit separators.
        if (raw.indexOf('_') >= 0) return true

        // Complex / imaginary suffix.
        val last = raw[raw.length - 1]
        if (last == 'j' || last == 'J') return true

        // Radix prefix: 0x / 0o / 0b (case-insensitive).
        if (raw.length >= 2 && raw[0] == '0') {
            when (raw[1].lowercaseChar()) {
                'x', 'o', 'b' -> return true
            }
        }

        return false
    }

    private fun highlight(holder: AnnotationHolder, baseOffset: Int, start: Int, end: Int) {
        val range = TextRange(baseOffset + start, baseOffset + end)
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(range)
            .textAttributes(NUMERIC_LITERAL)
            .create()
    }

    companion object {
        /**
         * Distinct attribute key for separator / radix / complex literals. Defaults to the
         * platform NUMBER color so it reads as a number out of the box but can be themed
         * independently from the plain lexer NUMBER key.
         */
        @JvmField
        val NUMERIC_LITERAL: TextAttributesKey = TextAttributesKey.createTextAttributesKey(
            "BASEDPYTHON_NUMERIC_LITERAL_SPECIAL",
            DefaultLanguageHighlighterColors.NUMBER
        )
    }
}
