package dev.basedpython.pycharm.lang.psi

import com.intellij.psi.tree.IElementType
import dev.basedpython.pycharm.lang.BasedPythonLanguage

/** Composite element type produced by the indent-aware parser. */
class BasedPythonElementType(debugName: String) : IElementType(debugName, BasedPythonLanguage)

object BasedPythonElementTypes {
    @JvmField val FUNCTION_DECLARATION: IElementType = BasedPythonElementType("BY_FUNCTION_DECLARATION")
    @JvmField val CLASS_DECLARATION: IElementType = BasedPythonElementType("BY_CLASS_DECLARATION")
    @JvmField val IMPORT_STATEMENT: IElementType = BasedPythonElementType("BY_IMPORT_STATEMENT")
    @JvmField val PARAMETER_LIST: IElementType = BasedPythonElementType("BY_PARAMETER_LIST")
    @JvmField val PARAMETER: IElementType = BasedPythonElementType("BY_PARAMETER")
    @JvmField val BLOCK: IElementType = BasedPythonElementType("BY_BLOCK")
    @JvmField val DECORATOR: IElementType = BasedPythonElementType("BY_DECORATOR")
    @JvmField val STATEMENT: IElementType = BasedPythonElementType("BY_STATEMENT")
}
