package dev.basedpython.pycharm.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources

/**
 * basedpython written in its own syntax — a `.by` or `.byi` — where `data class`, `enum class`,
 * `let` and `override def` are the language rather than syntax errors.
 *
 * The other half of [BasedPythonPyTemplateContextType], and a child of `BASED_PYTHON` the same way:
 * a template that writes basedpython's own forms names this one, so it is not offered in a `.py`
 * this plugin owns, which is plain Python.
 */
class BasedPythonByTemplateContextType : TemplateContextType("basedpython .by") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        if (file.language !== BasedPythonLanguage) return false
        // The view provider's file, as in BasedPythonPyTemplateContextType: completion's copy has none.
        return BasedPythonSources.isWrittenInBasedPython(file.viewProvider.virtualFile)
    }
}
