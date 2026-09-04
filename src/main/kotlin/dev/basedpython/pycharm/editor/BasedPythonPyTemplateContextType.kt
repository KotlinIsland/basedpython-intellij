package dev.basedpython.pycharm.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources

/**
 * basedpython source the transpiler does **not** rewrite — a `.py` this plugin owns.
 *
 * A child of [BasedPythonTemplateContextType], so a template that names only `BASED_PYTHON` is
 * still offered here: the platform drops a base context from the applicable set when a more
 * specific one matches, and `TemplateContext.isEnabled` then walks the base chain to find the value
 * (`TemplateManagerImpl.getDirectlyApplicableContextTypes`, `TemplateContext.isEnabledNoSync`). The
 * narrowing only bites the other way — a template that names `BASED_PYTHON_PY` is *not* offered in
 * a `.by`.
 *
 * That is the whole point of it. Some boilerplate is boilerplate only where the interpreter runs
 * what was written: in a `.by`, basedpython generates the `if __name__ == "__main__"` guard from
 * `def main`, and a hand-written one *stops* it doing so — see
 * [dev.basedpython.pycharm.run.main.ByMainSignature.invokesMain], which is what turns off the
 * generated argument parser, the gutter run icon and the argument form.
 */
class BasedPythonPyTemplateContextType : TemplateContextType("basedpython .py") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        if (file.language !== BasedPythonLanguage) return false
        // The view provider's file, not `PsiFile.getVirtualFile()`: completion runs against a copy
        // of the file whose `getVirtualFile()` is null, and a null there would read as "not a `.by`"
        // — offering the guard in the one place it must not appear.
        return !BasedPythonSources.hasGeneratedEntryPoint(file.viewProvider.virtualFile)
    }
}
