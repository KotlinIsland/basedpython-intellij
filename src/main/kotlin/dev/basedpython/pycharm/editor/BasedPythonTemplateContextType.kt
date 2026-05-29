package dev.basedpython.pycharm.editor

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType
import dev.basedpython.pycharm.lang.BasedPythonLanguage

class BasedPythonTemplateContextType : TemplateContextType("BasedPython") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file
        return file.language === BasedPythonLanguage
    }
}
