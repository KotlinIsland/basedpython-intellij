package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * Code block support for basedpython (`.by`): what the platform calls the *markers* of a compound
 * statement (its clause keywords) and the *range* that statement spans. [BlockClauses] computes
 * both from document text, since the `.by` PSI is flat and the `by` server has no request for it.
 *
 * What this buys, beyond the keyword highlighting that
 * [BasedPythonKeywordHighlightUsagesHandlerFactory] does:
 *
 *  - *Move Caret to Matching Brace* (`Ctrl+Shift+M`) with the caret on a clause keyword — the
 *    platform's `MatchBraceAction` asks the brace matcher first and falls through to
 *    `CodeBlockSupportHandler.findCodeBlockRange`, so `if` jumps to the end of its last `else`
 *    branch and back. Nothing else offers this for an indentation-delimited language.
 *  - The marker ranges the identifier highlighting pass draws when no custom highlight-usages
 *    handler claimed the caret — i.e. whenever the `by` server's document highlights are off.
 *
 * Only the clause keywords are markers. The `return`s of a `def` and the `break`s of a loop
 * highlight with their block ([BlockClauses.familyAt]) but do not delimit it, and a marker range is
 * what the platform navigates between and draws as the block's edges.
 *
 * `getCodeBlockMarkerRanges` is called with the leaf under the caret, so the keyword's own start
 * offset is the offset to ask about; a caret sitting just past the keyword has already been
 * adjusted onto it by `TargetElementUtil.adjustOffset`.
 */
class BasedPythonCodeBlockSupportHandler : CodeBlockSupportHandler {

    override fun getCodeBlockMarkerRanges(element: PsiElement): List<TextRange> =
        chainAt(element)?.clauses?.map { it.range } ?: emptyList()

    override fun getCodeBlockRange(element: PsiElement): TextRange =
        chainAt(element)?.blockRange ?: TextRange.EMPTY_RANGE

    private fun chainAt(element: PsiElement): BlockClauses.Chain? {
        val file = element.containingFile as? BasedPythonFile ?: return null
        return BlockClauses.chainAt(file.viewProvider.contents, element.textRange.startOffset)
    }
}
