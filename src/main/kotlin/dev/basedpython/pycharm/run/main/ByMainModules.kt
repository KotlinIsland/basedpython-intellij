package dev.basedpython.pycharm.run.main

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile

/** The line [line] of this document holds, without its terminator. */
internal fun Document.lineTextAt(line: Int): String =
    getText(com.intellij.openapi.util.TextRange(getLineStartOffset(line), getLineEndOffset(line)))

/**
 * Finds the `main` behind a run configuration's module.
 *
 * A `by run` configuration names a module, not a file; the argument form needs the signature that
 * module's entry point declares, so the name has to be walked back to the `.by` it came from.
 */
internal object ByMainModules {

    /**
     * The file `by run <module>` would run, or null when nothing under the project's roots matches.
     *
     * The reverse of [dev.basedpython.pycharm.run.moduleNameFor]: dots are directory separators,
     * and the roots are tried in the same order that builds the name.
     */
    fun fileFor(project: Project, module: String): VirtualFile? {
        val relative = module.trim().replace('.', '/') + ".by"
        if (relative == ".by") return null
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.toList() +
            ProjectRootManager.getInstance(project).contentRoots.toList() +
            listOfNotNull(project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) })
        return roots.firstNotNullOfOrNull { it.findFileByRelativePath(relative) }
    }

    /** The entry point [file] declares, read from the editor's copy when it has unsaved edits. */
    fun mainIn(project: Project, file: VirtualFile): ByMainFunction? =
        ReadAction.compute<ByMainFunction?, RuntimeException> {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            mainIn(document)
        }

    /**
     * The entry point [document] declares, or null when it has none — including when the module
     * invokes `main` itself, which leaves basedpython no guard to generate and so no command-line
     * interface to fill in.
     */
    fun mainIn(document: Document): ByMainFunction? {
        val lineText = { line: Int -> document.lineTextAt(line) }
        if (ByMainSignature.invokesMain(lineText, document.lineCount)) return null
        return ByMainSignature.find(lineText, document.lineCount)
    }

    /** The entry point a run configuration's [module] declares. */
    fun mainFor(project: Project, module: String): ByMainFunction? {
        val file = fileFor(project, module) ?: return null
        return mainIn(project, file)
    }
}
