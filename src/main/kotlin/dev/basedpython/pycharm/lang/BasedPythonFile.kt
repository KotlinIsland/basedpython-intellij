package dev.basedpython.pycharm.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.FileViewProvider

class BasedPythonFile(viewProvider: FileViewProvider) : PsiFileBase(viewProvider, BasedPythonLanguage) {
    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE
    override fun toString(): String = "BasedPython File"
}
