package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.editorActions.BackspaceModeOverride
import com.intellij.codeInsight.editorActions.SmartBackspaceMode
import com.intellij.psi.PsiFile

/**
 * Backspace in a `.by` file's leading indentation goes back to the previous indent stop.
 *
 * The platform does this itself — it is *Unindent: To nearest indent position* — measuring in
 * logical columns, so a tab counts as the columns it spans, and stepping by the file's code-style
 * indent size. Its default, *To proper indent position*, asks where the line belongs, which for
 * `.by` is `by`'s outline of the text and is there only for the revision the server last read, so
 * Backspace would act one way just after a keystroke and another a moment later. This turns that
 * default into the nearest-stop mode for basedpython only, which is what Shell Script does for
 * want of any answer. A user who switched unindenting off keeps it off.
 */
class BasedPythonBackspaceModeOverride : BackspaceModeOverride() {

    override fun getBackspaceMode(file: PsiFile, mode: SmartBackspaceMode): SmartBackspaceMode =
        if (mode == SmartBackspaceMode.AUTOINDENT) SmartBackspaceMode.INDENT else mode
}
