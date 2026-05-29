package dev.basedpython.pycharm.lang.psi

import com.intellij.extapi.psi.ASTWrapperPsiElement
import com.intellij.lang.ASTNode
import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import dev.basedpython.pycharm.lang.BasedPythonTokenTypes
import javax.swing.Icon

/**
 * PSI classes for the additive composite tree. Everything tolerant: name lookups return null
 * rather than throwing when the tree is incomplete (error recovery may leave nodes partial).
 */

/** Finds the first IDENTIFIER leaf that is a *direct* child of [node] (the declared name). */
private fun firstDirectIdentifier(element: PsiElement): PsiElement? {
    var child = element.firstChild
    while (child != null) {
        if (child.node?.elementType == BasedPythonTokenTypes.IDENTIFIER) return child
        child = child.nextSibling
    }
    return null
}

open class ByStatement(node: ASTNode) : ASTWrapperPsiElement(node)

class ByBlock(node: ASTNode) : ASTWrapperPsiElement(node)

class ByDecorator(node: ASTNode) : ASTWrapperPsiElement(node)

class ByImport(node: ASTNode) : ASTWrapperPsiElement(node)

abstract class ByNamedElement(node: ASTNode) : ASTWrapperPsiElement(node), PsiNameIdentifierOwner {
    override fun getNameIdentifier(): PsiElement? = firstDirectIdentifier(this)

    override fun getName(): String? = nameIdentifier?.text

    override fun setName(name: String): PsiElement {
        val id = nameIdentifier ?: throw IncorrectOperationException("No name identifier to rename")
        val newLeaf = com.intellij.psi.impl.source.tree.Factory.createSingleLeafElement(
            BasedPythonTokenTypes.IDENTIFIER, name, 0, name.length, null, manager
        )
        id.node.treeParent.replaceChild(id.node, newLeaf)
        return this
    }
}

class ByFunction(node: ASTNode) : ByNamedElement(node) {
    val parameterList: ByParameter?
        get() = PsiTreeUtil.getChildOfType(this, ByParameter::class.java)

    override fun getPresentation(): ItemPresentation = presentationFor(this, "function")
}

class ByClass(node: ASTNode) : ByNamedElement(node) {
    override fun getPresentation(): ItemPresentation = presentationFor(this, "class")
}

class ByParameter(node: ASTNode) : ByNamedElement(node) {
    override fun getPresentation(): ItemPresentation = presentationFor(this, "parameter")
}

private fun presentationFor(element: ByNamedElement, kind: String): ItemPresentation =
    object : ItemPresentation {
        override fun getPresentableText(): String = element.name ?: "<anonymous $kind>"
        override fun getLocationString(): String? = element.containingFile?.name
        override fun getIcon(unused: Boolean): Icon? = null
    }
