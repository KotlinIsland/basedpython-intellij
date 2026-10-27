package dev.basedpython.pycharm.editor.smart.python

import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.psi.PsiFile
import com.jetbrains.python.documentation.PyDocumentationSettings
import com.jetbrains.python.documentation.docstrings.DocStringFormat
import dev.basedpython.pycharm.editor.smart.ByDocstringFormatProvider
import dev.basedpython.pycharm.lsp.ext.ByDocstringFormat

/**
 * PyCharm's *Docstring format* (Settings | Tools | Python Integrated Tools) for the module a `.by`
 * file is in — the application's default for new projects when it is in none, as for `.py` — so a
 * `.by` stub is written in the format a `.py` one is.
 *
 * Every format PyCharm has, `by` writes, so the setting is always sent and never left to `by`'s
 * reading of the file. *Plain* is the summary line alone in both. The `when` is exhaustive over
 * PyCharm's enum on purpose: a format PyCharm adds stops this compiling until it is mapped.
 */
class PythonDocstringFormatProvider : ByDocstringFormatProvider {

    override fun format(file: PsiFile): ByDocstringFormat {
        val settings = PyDocumentationSettings.getInstance(ModuleUtilCore.findModuleForFile(file))
        return when (settings.getFormatForFile(file)) {
            DocStringFormat.REST -> ByDocstringFormat.REST
            DocStringFormat.GOOGLE -> ByDocstringFormat.GOOGLE
            DocStringFormat.NUMPY -> ByDocstringFormat.NUMPY
            DocStringFormat.PLAIN -> ByDocstringFormat.PLAIN
        }
    }
}
