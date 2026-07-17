package dev.basedpython.pycharm.lang

import com.intellij.lang.Language

object BasedPythonLanguage : Language("basedpython") {
    private fun readResolve(): Any = BasedPythonLanguage
    override fun getDisplayName(): String = "basedpython"
}
