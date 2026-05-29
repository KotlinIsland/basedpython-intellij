package dev.basedpython.pycharm.lang.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.psi.PsiElement

/** Maps a composite [ASTNode]'s element type to its PSI wrapper. */
object BasedPythonPsiFactory {
    fun createElement(node: ASTNode): PsiElement = when (node.elementType) {
        BasedPythonElementTypes.FUNCTION_DECLARATION -> ByFunction(node)
        BasedPythonElementTypes.CLASS_DECLARATION -> ByClass(node)
        BasedPythonElementTypes.IMPORT_STATEMENT -> ByImport(node)
        BasedPythonElementTypes.PARAMETER -> ByParameter(node)
        BasedPythonElementTypes.BLOCK -> ByBlock(node)
        BasedPythonElementTypes.DECORATOR -> ByDecorator(node)
        BasedPythonElementTypes.STATEMENT -> ByStatement(node)
        // PARAMETER_LIST and any unforeseen composite: a plain wrapper keeps the tree walkable.
        else -> ASTWrapperPsiElement(node)
    }
}
