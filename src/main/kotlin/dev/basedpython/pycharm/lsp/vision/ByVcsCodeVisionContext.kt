package dev.basedpython.pycharm.lsp.vision

import com.intellij.codeInsight.hints.VcsCodeVisionLanguageContext
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lang.BasedPythonFile
import java.awt.event.MouseEvent

/**
 * The code author above each declaration of a `.by` file — *Morgan +2* — as Java, Kotlin and Python
 * put one above each class and function, from the platform's own VCS code vision.
 *
 * The platform walks the file's PSI and asks of each element whether it is a declaration. A `.by`
 * file's PSI is a token per leaf, so the declaration is said to be the token it starts with — its
 * first decorator's `@`, or its `def` or `class` — which is where the platform anchors the author,
 * and which declarations there are is `by`'s outline ([ByDeclarations]). The author is worked out
 * from the declaration's name to its end, the range the platform uses for a named element of any
 * language, rather than from that one token.
 *
 * Switched off with the others under *Settings | Editor | Inlay Hints | Code vision | Code author*.
 */
class ByVcsCodeVisionContext : VcsCodeVisionLanguageContext {

    override fun isAccepted(element: PsiElement): Boolean = element.firstChild == null && declaration(element) != null

    override fun computeEffectiveRange(element: PsiElement): TextRange =
        declaration(element)?.effectiveRange ?: super.computeEffectiveRange(element)

    /** Nothing more: the platform has already opened the annotations at the click. */
    override fun handleClick(mouseEvent: MouseEvent, editor: Editor, element: PsiElement) {}

    private fun declaration(element: PsiElement): ByDeclarations.Declaration? {
        val file = element.containingFile as? BasedPythonFile ?: return null
        return ByDeclarations.getInstance(file.project).forFile(file)?.startingAt(element.textRange.startOffset)
    }
}
