package dev.basedpython.pycharm.run.test.tree

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.run.model.walk

/**
 * Finds the source a pytest node id came from, and the place in it.
 *
 * Shared by everything that turns a node id into a place in the project: the test tree of a run
 * ([ByTestLocator]) and the collected node view
 * ([dev.basedpython.pycharm.run.test.node.ByTestNodeActions]). Splitting it out is what lets the node
 * view navigate without touching the SM test runner, which is an optional dependency of this plugin.
 */
internal object ByTestSources {

    /** A file, and the offset of a test's name in it — `0` for the file itself. */
    data class Place(val file: VirtualFile, val offset: Int)

    /**
     * Where [nodeId] was declared, or null when it names no file this project holds.
     *
     * The file is the one `by` says `by run` stages at the node id's path
     * ([ByProgramModel.stagedFile]) when [transpiled] allows it, and otherwise the `.py` plain pytest
     * collected at that path in the project. The place in it is the name `by/testItems` gave the test
     * whose names the node id carries: the checker knows which of two `def test_x` pytest collects, a
     * search of the text does not. A test `by` has not described is the file itself.
     *
     * @param transpiled true for a node id from `by run pytest`, false for one from plain pytest, null
     *   when the caller cannot tell — a staged file is then looked for first
     */
    fun locate(project: Project, nodeId: String, transpiled: Boolean?): Place? {
        val location = ByTestLocations.parse(nodeId) ?: return null
        val model = ByProgramModel.getInstance(project)
        val file = (if (transpiled != false) model.stagedFile(location.file) else null)
            ?: (if (transpiled != true) findSourceFile(project, location.file) else null)
            ?: return null
        if (location.symbols.isEmpty()) return Place(file, 0)
        // Waits for the file's current answer off the EDT; on it, an answer for an older revision is
        // still better than none, and its offset is clamped to the document below.
        val items = model.testItems(file) ?: model.projectTests()?.byFile?.get(file) ?: return Place(file, 0)
        val item = items.walk().firstOrNull { it.symbols == location.symbols } ?: return Place(file, 0)
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return Place(file, 0)
        val start = item.selectionRange.start
        if (start.line >= document.lineCount) return Place(file, 0)
        val offset = document.getLineStartOffset(start.line) + start.character
        return Place(file, offset.coerceAtMost(document.getLineEndOffset(start.line)))
    }

    /**
     * The file at [relativePath] under some content root, or the project base.
     *
     * Content roots first and in order, because a node id plain pytest reports is relative to its
     * rootdir and a multi-root project can hold the same relative path more than once; the base path
     * is the fallback for a project whose roots are not registered.
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
     * The path [file] is known by inside a node id plain pytest reports — the inverse of
     * [findSourceFile].
     *
     * Relative to the project base first, because that is where plain pytest runs and so the root
     * every such node id is written against; content roots are the fallback for a file that lives
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
