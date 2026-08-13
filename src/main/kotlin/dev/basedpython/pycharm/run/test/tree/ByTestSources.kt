package dev.basedpython.pycharm.run.test.tree

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

/**
 * Finds the `.by` file a pytest node id came from.
 *
 * Shared by everything that turns a node id into a place in the project: the test tree of a run
 * ([ByTestLocator]) and the collected node view
 * ([dev.basedpython.pycharm.run.test.node.ByTestNodeActions]). Splitting the path lookup out is
 * what lets the node view navigate without touching the SM test runner, which is an optional
 * dependency of this plugin.
 */
internal object ByTestSources {

    /**
     * The file at [relativePath] under some content root, or the project base.
     *
     * Content roots first and in order, because a node id is relative to `by run`'s working
     * directory and a multi-root project can hold the same relative path more than once; the base
     * path is the fallback for a project whose roots are not registered.
     */
    fun findSourceFile(project: Project, relativePath: String): VirtualFile? {
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            root.findFileByRelativePath(relativePath)?.takeIf { !it.isDirectory }?.let { return it }
        }
        val base = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$base/$relativePath")
            ?.takeIf { !it.isDirectory }
    }

    /**
     * The path [file] is known by inside a node id — the inverse of [findSourceFile].
     *
     * Relative to the project base first, because that is `by run`'s working directory and so the
     * root every node id is written against; content roots are the fallback for a file that lives
     * outside the base directory. Null when [file] is under neither.
     */
    fun relativePath(project: Project, file: VirtualFile): String? {
        project.basePath
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?.let { base -> VfsUtilCore.getRelativePath(file, base, '/') }
            ?.let { return it }
        for (root in ProjectRootManager.getInstance(project).contentRoots) {
            VfsUtilCore.getRelativePath(file, root, '/')?.let { return it }
        }
        return null
    }
}
