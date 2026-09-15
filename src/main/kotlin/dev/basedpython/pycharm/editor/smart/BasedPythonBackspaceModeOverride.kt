package dev.basedpython.pycharm.editor.smart

import com.intellij.codeInsight.editorActions.BackspaceModeOverride
import com.intellij.codeInsight.editorActions.SmartBackspaceMode
import com.intellij.psi.PsiFile

/**
 * Backspace in a `.by` file's leading indentation goes back to the previous indent stop.
 *
 * The platform does this itself — it is *Unindent: To nearest indent position* — measuring in
 * logical columns, so a tab counts as the columns it spans, and stepping by the file's code-style
 * indent size. Its default, *To proper indent position*, asks the formatter where the line belongs,
 * and `.by` has no formatting model to ask, so under that default Backspace deleted one space.
 * This turns that default into the nearest-stop mode for basedpython only, which is what Shell
 * Script does for the same reason. A user who switched unindenting off keeps it off.
 */
class BasedPythonBackspaceModeOverride : BackspaceModeOverride() {

    override fun getBackspaceMode(file: PsiFile, mode: SmartBackspaceMode): SmartBackspaceMode =
        if (mode == SmartBackspaceMode.AUTOINDENT) SmartBackspaceMode.INDENT else mode
}
