package dev.basedpython.pycharm.editor.smart

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile
import dev.basedpython.pycharm.lsp.ext.ByDocstringFormat

/**
 * The docstring format an IDE's own settings ask a `.by` file's stubs to be written in
 * ([ByDocstringStubs] sends it with `by/docstringStub`).
 *
 * The one such setting is PyCharm's *Docstring format* (Settings | Tools | Python Integrated
 * Tools), which belongs to the Python plugin and is read by
 * [dev.basedpython.pycharm.editor.smart.python.PythonDocstringFormatProvider], registered under the
 * optional dependency on it (basedpython-python.xml). With no provider — an IDE without the Python
 * plugin — no format is sent and `by` picks one from the file's other docstrings.
 */
interface ByDocstringFormatProvider {

    /** The format [file]'s stubs are to be written in; `null` to leave it to `by`. Called in a read action. */
    fun format(file: PsiFile): ByDocstringFormat?

    companion object {
        val EP_NAME: ExtensionPointName<ByDocstringFormatProvider> = ExtensionPointName("dev.basedpython.ide.docstringFormat")

        /** What the first provider with an answer says for [file]; `null`, `by`'s choice, when none has one. */
        fun formatFor(file: PsiFile): ByDocstringFormat? = EP_NAME.extensionList.firstNotNullOfOrNull { it.format(file) }
    }
}
