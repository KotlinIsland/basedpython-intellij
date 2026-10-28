package dev.basedpython.pycharm.lang

import com.intellij.extapi.psi.PsiFileBase
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.Project
import com.intellij.psi.FileViewProvider
import com.intellij.psi.PsiCodeFragment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.SingleRootFileViewProvider
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.LightVirtualFile

/**
 * A basedpython expression typed in a field of the IDE's, in the context of [context] — what a
 * suggested Change Signature's popup asks for each new parameter's value in calls.
 *
 * The platform's own code fragment class is internal, so this is a file of its own kind over a
 * light file.
 */
class BasedPythonCodeFragment private constructor(viewProvider: FileViewProvider, private val context: PsiElement?) :
    PsiFileBase(viewProvider, BasedPythonLanguage), PsiCodeFragment {

    private var resolveScope: GlobalSearchScope? = null

    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE

    override fun getContext(): PsiElement? = context

    override fun forceResolveScope(scope: GlobalSearchScope) {
        resolveScope = scope
    }

    override fun getForcedResolveScope(): GlobalSearchScope? = resolveScope

    override fun toString(): String = "basedpython code fragment"

    companion object {
        /** A fragment named [name] holding [text], in the context of [context]. */
        fun create(project: Project, name: String, text: String, context: PsiElement?): PsiCodeFragment {
            val file = LightVirtualFile(name, BasedPythonFileType.INSTANCE, text)
            val provider = SingleRootFileViewProvider(PsiManager.getInstance(project), file, true)
            return BasedPythonCodeFragment(provider, context).also(provider::forceCachedPsi)
        }
    }
}
