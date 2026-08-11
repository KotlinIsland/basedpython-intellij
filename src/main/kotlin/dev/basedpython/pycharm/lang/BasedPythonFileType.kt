package dev.basedpython.pycharm.lang

import com.intellij.openapi.fileTypes.LanguageFileType
import dev.basedpython.pycharm.BasedPythonIcons
import javax.swing.Icon

class BasedPythonFileType private constructor() : LanguageFileType(BasedPythonLanguage) {

    override fun getName(): String = "basedpython"
    override fun getDescription(): String = "basedpython source file"
    override fun getDefaultExtension(): String = "by"
    override fun getIcon(): Icon = ICON

    companion object {
        @JvmField
        val INSTANCE: BasedPythonFileType = BasedPythonFileType()

        private val ICON: Icon = BasedPythonIcons.Logo
    }
}
