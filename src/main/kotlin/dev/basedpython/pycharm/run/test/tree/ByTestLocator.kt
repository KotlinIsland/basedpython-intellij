package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.Location
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * Best-effort [SMTestLocator] that maps a `path/to/test_x.py` (or `path::test`)
 * protocol URL emitted as a suite/test location into an openable file location.
 *
 * Only the file is resolved (no line/element navigation), which is enough for the
 * "jump to source" action on tree nodes. Returns an empty list when the path
 * cannot be resolved, leaving the node non-navigable rather than throwing.
 */
object ByTestLocator : SMTestLocator {

    const val PROTOCOL: String = "by_test"

    override fun getLocation(
        protocol: String,
        path: String,
        project: Project,
        scope: GlobalSearchScope,
    ): List<Location<*>> {
        if (protocol != PROTOCOL) return emptyList()
        val filePath = path.substringBefore("::").trim()
        if (filePath.isEmpty()) return emptyList()
        val vFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(vFile) ?: return emptyList()
        return listOf(com.intellij.execution.PsiLocation.fromPsiElement(psiFile))
    }
}
