package dev.basedpython.pycharm.navigation

import com.intellij.navigation.GotoRelatedItem
import com.intellij.navigation.GotoRelatedProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import dev.basedpython.pycharm.lang.BasedPythonFileType
import java.nio.file.Path
import java.nio.file.Paths

/**
 * "Go to Related" provider linking a `.by` source file with its generated `.py`
 * counterpart under `<projectBase>/out/` and vice-versa.
 *
 * The mapping mirrors [dev.basedpython.pycharm.transpile.GoToGeneratedPyAction]:
 *   `<base>/some/dir/foo.by`  <->  `<base>/out/some/dir/foo.py`
 *
 * Items are only returned when the counterpart actually exists on disk.
 */
class BasedPythonRelatedProvider : GotoRelatedProvider() {

    override fun getItems(psiElement: PsiElement): List<GotoRelatedItem> {
        val project = psiElement.project
        val file = psiElement.containingFile?.virtualFile ?: return emptyList()
        val basePath = project.basePath ?: return emptyList()
        val base = Paths.get(basePath)

        val counterpart: VirtualFile? = when {
            isByFile(file) -> resolveGeneratedPy(file, base)
            isGeneratedPy(file, base) -> resolveSourceBy(file, base)
            else -> null
        }

        val target = counterpart?.takeIf { it.isValid && it.exists() } ?: return emptyList()
        val psiTarget = PsiManager.getInstance(project).findFile(target) ?: return emptyList()
        val groupName = if (isByFile(file)) "Generated Python" else "BasedPython Source"
        return listOf(GotoRelatedItem(psiTarget, groupName))
    }

    /** `<base>/sub/foo.by` -> `<base>/out/sub/foo.py` */
    private fun resolveGeneratedPy(file: VirtualFile, base: Path): VirtualFile? {
        val out = resolveOutPath(file, base) ?: return null
        return LocalFileSystem.getInstance().findFileByNioFile(out)
    }

    /** `<base>/out/sub/foo.py` -> `<base>/sub/foo.by` */
    private fun resolveSourceBy(file: VirtualFile, base: Path): VirtualFile? {
        val outRoot = base.resolve("out")
        val filePath = file.toNioPath()
        return try {
            val relative = outRoot.relativize(filePath).toString()
            val withByExt = relative.replaceFirst(Regex("\\.py$", RegexOption.IGNORE_CASE), ".by")
            LocalFileSystem.getInstance().findFileByNioFile(base.resolve(withByExt))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun resolveOutPath(file: VirtualFile, base: Path): Path? =
        try {
            val relative = base.relativize(file.toNioPath()).toString()
            val withPyExt = relative.replaceFirst(Regex("\\.by$", RegexOption.IGNORE_CASE), ".py")
            base.resolve("out").resolve(withPyExt)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun isByFile(file: VirtualFile): Boolean =
        file.fileType == BasedPythonFileType.INSTANCE || file.extension.equals("by", ignoreCase = true)

    private fun isGeneratedPy(file: VirtualFile, base: Path): Boolean {
        if (!file.extension.equals("py", ignoreCase = true)) return false
        return try {
            val outRoot = base.resolve("out")
            file.toNioPath().startsWith(outRoot)
        } catch (_: Exception) {
            false
        }
    }
}
