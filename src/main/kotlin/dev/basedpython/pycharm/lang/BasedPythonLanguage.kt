package dev.basedpython.pycharm.lang

import com.intellij.lang.Language

object BasedPythonLanguage : Language("BasedPython") {
    private fun readResolve(): Any = BasedPythonLanguage
    override fun getDisplayName(): String = "BasedPython"
}
