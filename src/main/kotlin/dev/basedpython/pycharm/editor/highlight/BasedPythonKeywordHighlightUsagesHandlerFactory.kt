package dev.basedpython.pycharm.editor.highlight

import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerBase
import com.intellij.codeInsight.highlighting.HighlightUsagesHandlerFactory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.Consumer
import dev.basedpython.pycharm.lang.BasedPythonFile

/**
 * "Matched same-keyword" highlighting for basedpython (`.by`) files: with the caret on `if`, its
 * `elif`s and `else` light up too, the way the platform pairs `if`/`else` in a braced language.
 * [BlockClauses] decides what pairs with what.
 *
 * Registered `order="first"`. `by` advertises `documentHighlightProvider`, so the platform's own
 * `LspHighlightUsagesHandlerFactory` returns a handler for *every* caret position in a `.by` file,
 * and `HighlightUsagesHandler.createCustomHandler` keeps the first non-null one — whichever factory
 * sorts earlier decides whether keywords highlight at all, and the LSP one highlights nothing here
 * (`by` answers `null` for keyword positions). This factory already sorted ahead of it while
 * registered plain, so the attribute pins a relation that held by accident of load order.
 *
 * That same custom handler is also why the platform's [BasedPythonCodeBlockSupportHandler] markers
 * cannot carry this on their own: `IdentifierHighlightingComputer` collects code block markers only
 * when no custom handler claimed the caret, or when the one that did returns `highlightReferences`.
 * So the keywords are highlighted from here, and the code block handler serves navigation.
 */
class BasedPythonKeywordHighlightUsagesHandlerFactory : HighlightUsagesHandlerFactory {

    override fun createHighlightUsagesHandler(
        editor: Editor,
        file: PsiFile,
    ): HighlightUsagesHandlerBase<PsiElement>? {
        if (file !is BasedPythonFile) return null
        val chain = BlockClauses.chainAt(editor.document.charsSequence, editor.caretModel.offset)
            ?: return null
        return Handler(editor, file, chain.clauses.map { it.range })
    }

    private class Handler(
        editor: Editor,
        private val file: PsiFile,
        private val ranges: List<TextRange>,
    ) : HighlightUsagesHandlerBase<PsiElement>(editor, file) {

        override fun getTargets(): List<PsiElement> = listOf(file)

        override fun selectTargets(
            targets: List<PsiElement>,
            selectionConsumer: Consumer<in List<PsiElement>>,
        ) {
            selectionConsumer.consume(targets)
        }

        override fun computeUsages(targets: List<PsiElement>) {
            myReadUsages.addAll(ranges)
        }
    }
}
