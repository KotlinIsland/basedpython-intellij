package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.Location
import com.intellij.execution.PsiLocation
import com.intellij.execution.testframework.sm.runner.SMTestLocator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope

/**
 * Makes test-tree nodes navigable: resolves the `by_test://` URLs the parser attaches to suites and
 * tests back to the `.by` file and declaration they came from.
 *
 * pytest runs against the transpiled tree, so its node ids are paths relative to `by run`'s temp
 * directory naming `.py` files. Relative paths survive transpilation, so the same path under a
 * content root with a `.by` extension is the source — [ByTestLocations] does that rewrite, this
 * resolves it against the project.
 *
 * Every failure degrades to "not navigable" rather than throwing: a node the IDE cannot open is a
 * missing convenience, an exception during tree building is a broken run.
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
        val location = ByTestLocations.parse(path) ?: return emptyList()
        val file = findSourceFile(project, location.file) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return emptyList()

        val element: PsiElement = location.symbols
            .takeIf { it.isNotEmpty() }
            ?.let { ByTestLocations.declarationOffset(psiFile.text, it) }
            ?.let { psiFile.findElementAt(it) }
            ?: psiFile

        return listOf(PsiLocation.fromPsiElement(element))
    }

    /**
     * The `.by` file at [relativePath] under some content root, or the project base.
     *
     * Content roots first and in order, because a node id is relative to `by run`'s working
     * directory and a multi-root project can hold the same relative path more than once; the base
     * path is the fallback for a project whose roots are not registered.
     */
    private fun findSourceFile(project: Project, relativePath: String): VirtualFile? {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        for (root in roots) {
            root.findFileByRelativePath(relativePath)?.takeIf { !it.isDirectory }?.let { return it }
        }
        return project.baseDir(relativePath)
    }

    private fun Project.baseDir(relativePath: String): VirtualFile? =
        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath((basePath ?: return null) + "/" + relativePath)
            ?.takeIf { !it.isDirectory }
}
