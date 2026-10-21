package dev.basedpython.pycharm.editor.mover

import com.intellij.codeInsight.editorActions.moveLeftRight.MoveElementLeftRightHandler
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import dev.basedpython.pycharm.lang.ByRangeElement
import dev.basedpython.pycharm.lsp.outline.ByOutlines

/**
 * Move Element Left/Right (Ctrl+Alt+Shift+←/→) in a `.by` file: an argument, a parameter, an
 * element of a list, tuple, set or dict, a name of an import swaps places with the one beside it.
 *
 * Which elements make a list is `by`'s parse, from the lists in its outline (see [ByOutlines]): an
 * element is a node, so the commas of a lambda or a nested call among the arguments are not the
 * list's, and the `/` and bare `*` among parameters are elements of their own.
 *
 * The platform asks about each element from the token at the caret up to the file, and moves among
 * the elements of the first one that answers with the caret inside its list. In a `.by` file that is
 * the token itself, since there is nothing between a token and the file; the token is answered with
 * the innermost list around it. The file is never answered for — it holds every list, and which
 * one is meant is the caret's to say — so a selection spanning several tokens, which the platform
 * asks about only through the element holding all of them, moves nothing.
 *
 * Nothing moves while the outline is not for the text on screen, which is between an edit and
 * `by`'s answer for it: this runs on the EDT, and neither waits for the server nor guesses.
 */
class ByMoveElementLeftRightHandler : MoveElementLeftRightHandler() {

    override fun getMovableSubElements(element: PsiElement): Array<PsiElement> {
        if (element.firstChild != null) return PsiElement.EMPTY_ARRAY
        val file = element.containingFile as? BasedPythonFile ?: return PsiElement.EMPTY_ARRAY
        val document = file.viewProvider.document ?: return PsiElement.EMPTY_ARRAY
        // a token of a tree not yet brought up to the text is at offsets the outline does not use
        if (!PsiDocumentManager.getInstance(file.project).isCommitted(document)) return PsiElement.EMPTY_ARRAY
        val outline = ByOutlines.getInstance(file.project).current(document) ?: return PsiElement.EMPTY_ARRAY
        val list = outline.listAt(element.textRange.startOffset) ?: return PsiElement.EMPTY_ARRAY
        return list.map { ByRangeElement(file, it) }.toTypedArray()
    }
}
