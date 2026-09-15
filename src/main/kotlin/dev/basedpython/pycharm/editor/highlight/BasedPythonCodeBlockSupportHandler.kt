package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.CodeBlockSupportHandler
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lsp.outline.ByOutline
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Code block support for basedpython (`.by`): what the platform calls the *markers* of a compound
 * statement (its clause keywords) and the *range* that statement spans, both read off `by`'s parse
 * — see [ByOutlines].
 *
 * What this buys is *Move Caret to Matching Brace* (`Ctrl+Shift+M`) with the caret on a clause
 * keyword: the platform's `MatchBraceAction` asks the brace matcher first and falls through to
 * `CodeBlockSupportHandler.findCodeBlockRange`, so `if` jumps to the end of its last `else` branch
 * and back. Nothing else offers this for an indentation-delimited language.
 *
 * The keyword highlighting that goes with it is not here. It is `by`'s answer to
 * `textDocument/documentHighlight`, which the platform's LSP support asks at every caret position
 * — and which also lights up a `def`'s `return`s and a loop's `break`s, which are not markers:
 * a marker is what the platform navigates between and draws as the block's edges.
 *
 * A statement with a single clause keyword — a plain `if`, a `with`, a `def` — has nothing to pair
 * with and is not a block here. `getCodeBlockMarkerRanges` is called with the leaf under the caret,
 * so the keyword's own start offset is the offset to ask about; a caret just past the keyword has
 * already been moved onto it by `TargetElementUtil.adjustOffset`.
 *
 * `MatchBraceAction` asks on the EDT, where nothing waits for the server: a press before `by` has
 * answered for the text on screen finds no block, which is what the platform does for any language
 * without one.
 */
class BasedPythonCodeBlockSupportHandler : CodeBlockSupportHandler {

    override fun getCodeBlockMarkerRanges(element: PsiElement): List<TextRange> =
        blockAt(element)?.let { (statement, _) -> keywords(statement) } ?: emptyList()

    override fun getCodeBlockRange(element: PsiElement): TextRange =
        blockAt(element)?.second ?: TextRange.EMPTY_RANGE

    /** The statement one of whose clause keywords [element] starts, and the range of its block. */
    private fun blockAt(element: PsiElement): Pair<ByOutline.Statement, TextRange>? {
        val file = element.containingFile as? BasedPythonFile ?: return null
        val outline = ByOutlines.getInstance(file.project).forFile(file) ?: return null
        val offset = element.textRange.startOffset
        var found: ByOutline.Statement? = null
        outline.walk { statement, _ ->
            if (statement.clauses.any { it.keyword?.containsOffset(offset) == true }) found = statement
        }
        val statement = found ?: return null
        if (keywords(statement).size < 2) return null
        // From the first keyword, not the statement's first token: a decorator is not the block.
        return statement to TextRange(statement.clauses.first().range.startOffset, statement.range.endOffset)
    }

    private fun keywords(statement: ByOutline.Statement): List<TextRange> =
        statement.clauses.mapNotNull { it.keyword }
}
