package dev.basedpython.pycharm.debug.recompose

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/**
 * Opening the place a record points at.
 *
 * Always `file`/`line` of a location — the `.by` place when the build's source map covers the
 * generated file — and never the generated one, which is shown in a tooltip and nowhere else: the
 * transpiled tree lives in a temp directory nobody edits.
 */
internal object ByRecompositionNavigation {

    /**
     * Opens [location] in an editor and reports whether there was a location to open.
     *
     * Called on the EDT, from a double-click or an action. A file the VFS already knows opens at
     * once. One it does not — a file the IDE has never looked at — needs a refresh, which is I/O
     * and is not done on the EDT: it runs on a pooled thread and the editor opens when it is back,
     * or not at all when the path names nothing.
     */
    fun open(project: Project, location: ByTraceLocation?): Boolean {
        if (location == null) return false
        val fs = LocalFileSystem.getInstance()
        val known = fs.findFileByPath(location.file)
        if (known != null) {
            navigate(project, known, location.line)
            return true
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = fs.refreshAndFindFileByPath(location.file) ?: return@executeOnPooledThread
            ApplicationManager.getApplication().invokeLater({ navigate(project, found, location.line) }, project.disposed)
        }
        return true
    }

    private fun navigate(project: Project, file: VirtualFile, line: Int) {
        if (project.isDisposed || !file.isValid) return
        OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)
    }
}
