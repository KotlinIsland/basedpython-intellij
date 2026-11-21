package dev.basedpython.pycharm.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources

/**
 * A `.py` this plugin owns: the basedpython language, written in plain Python.
 *
 * One of the two halves of [BasedPythonTemplateContextType], the other being
 * [BasedPythonByTemplateContextType]. A template names the half whose syntax it writes — a `.py`
 * is not transpiled, so `enum class` there is a syntax error and the `enum` offered is
 * `class MyEnum(Enum)` — and names the base, `BASED_PYTHON`, only when what it writes is valid in
 * both.
 *
 * A child of `BASED_PYTHON`, so a template that names only the base is still offered here: the
 * platform drops a base context from the applicable set when a more specific one matches, and
 * `TemplateContext.isEnabled` then walks the base chain to find the value
 * (`TemplateManagerImpl.getDirectlyApplicableContextTypes`, `TemplateContext.isEnabledNoSync`). The
 * narrowing only bites the other way — a template that names `BASED_PYTHON_PY` is *not* offered in
 * a `.by`.
 *
 * Some boilerplate is boilerplate only where the interpreter runs what was written: in a `.by`,
 * basedpython generates the `if __name__ == "__main__"` guard from `def main`, and a hand-written
 * one *stops* it doing so — see `by/entryPoint`'s `moduleInvokesMain`
 * ([dev.basedpython.pycharm.run.model.ByEntryPoint]), which is what turns off the generated argument
 * parser and the argument form.
 */
class BasedPythonPyTemplateContextType : TemplateContextType("basedpython .py") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        if (file.language !== BasedPythonLanguage) return false
        // The view provider's file, not `PsiFile.getVirtualFile()`: completion runs against a copy
        // of the file whose `getVirtualFile()` is null, and a null there would read as "not a `.py`".
        return !BasedPythonSources.isWrittenInBasedPython(file.viewProvider.virtualFile)
    }
}
