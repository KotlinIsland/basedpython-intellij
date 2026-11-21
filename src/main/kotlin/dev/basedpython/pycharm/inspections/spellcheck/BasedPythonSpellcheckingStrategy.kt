package dev.basedpython.pycharm.inspections.spellcheck

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import dev.basedpython.pycharm.lsp.semantic.BySemanticTokens

/**
 * Runtime spellchecking strategy for basedpython. Spell-checks comments, string literals, and the
 * identifiers that *declare* a name; the platform's word splitter already handles camelCase /
 * snake_case boundaries (see [IdentifierSplitter] for the standalone version used in tests).
 *
 * Only declarations, as Python's own strategy does: a misspelt name is one mistake, made where it
 * is declared and fixed by renaming it there, and reporting every use of it repeats that one
 * finding — `itemz` used four times was five typos. Which identifier declares a name is `by`'s to
 * say, through its semantic tokens' `definition` modifier ([BySemanticTokens]); until it has said,
 * for the file as it is now, no identifier is checked.
 */
class BasedPythonSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun isMyContext(element: PsiElement): Boolean =
        element.containingFile is BasedPythonFile

    override fun getTokenizer(element: PsiElement): Tokenizer<*> =
        when (element.node?.elementType) {
            BasedPythonTokenTypes.COMMENT,
            BasedPythonTokenTypes.STRING -> TEXT_TOKENIZER
            BasedPythonTokenTypes.IDENTIFIER -> if (declares(element)) TEXT_TOKENIZER else EMPTY_TOKENIZER
            else -> EMPTY_TOKENIZER
        }

    private fun declares(identifier: PsiElement): Boolean {
        val file = identifier.containingFile ?: return false
        return BySemanticTokens.getInstance(file.project)
            .isDefinitionAt(file, identifier.textRange.startOffset) == true
    }
}

/**
 * Splits an identifier into camelCase / snake_case word segments.
 * Used by the runtime spellchecking strategy and independently testable.
 */
object IdentifierSplitter {
    /**
     * Yields (startOffset, endOffset) pairs for each word component of [text].
     * Splits on underscores and camelCase boundaries.
     */
    fun split(text: String, consumer: (Int, Int) -> Unit) {
        if (text.isBlank()) return
        // first split on underscores
        val parts = mutableListOf<Pair<Int, Int>>()
        var segStart = 0
        for (i in text.indices) {
            if (text[i] == '_') {
                if (i > segStart) parts += segStart to i
                segStart = i + 1
            }
        }
        if (segStart < text.length) parts += segStart to text.length

        // then split each underscore segment on camelCase boundaries
        for ((pStart, pEnd) in parts) {
            var wordStart = pStart
            for (i in pStart + 1 until pEnd) {
                val prev = text[i - 1]
                val curr = text[i]
                val next = if (i + 1 < pEnd) text[i + 1] else ' '
                when {
                    curr.isUpperCase() && prev.isLowerCase() -> {
                        if (i > wordStart) consumer(wordStart, i)
                        wordStart = i
                    }
                    curr.isUpperCase() && next.isLowerCase() && prev.isUpperCase() -> {
                        if (i > wordStart) consumer(wordStart, i)
                        wordStart = i
                    }
                }
            }
            if (pEnd > wordStart) consumer(wordStart, pEnd)
        }
    }
}
