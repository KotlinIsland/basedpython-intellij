package dev.basedpython.pycharm.navigation

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs

/**
 * "Go to Related" provider linking a `.by` source file with the `.py` `by build` writes it to, and
 * back.
 *
 * Both directions are `by`'s answer ([ByBuildOutputs]): the generated file sits in the build
 * directory at the source's *module* path, which only the build knows. The platform collects related
 * items in a background read action, so asking here is allowed.
 *
 * Items are only returned when the counterpart actually exists on disk.
 */
class BasedPythonRelatedProvider : GotoRelatedProvider() {

    override fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        val project = psiElement.project
        val file = psiElement.containingFile?.virtualFile ?: return emptyList()
        val source = isByFile(file)
        if (!source && !file.extension.equals("py", ignoreCase = true)) return emptyList()

        val answer = ByBuildOutputs.getInstance(project).of(file) ?: return emptyList()
        val counterpart = (if (source) answer.generated else answer.source) ?: return emptyList()
        val target = LocalFileSystem.getInstance().findFileByPath(counterpart)?.takeIf { it.isValid }
            ?: return emptyList()
        val psiTarget = PsiManager.getInstance(project).findFile(target) ?: return emptyList()
        val groupName = if (source) "Generated Python" else "basedpython Source"
        return listOf(GotoRelatedItem(psiTarget, groupName))
    }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)
}
