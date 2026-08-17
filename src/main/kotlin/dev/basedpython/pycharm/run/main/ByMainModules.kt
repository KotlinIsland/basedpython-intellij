package dev.basedpython.pycharm.run.main

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.dialect.BasedPythonSources

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
     * and the roots are tried in the same order that builds the name. Extensions are tried in
     * `by run`'s own order — `.by` before `.py`, because the transpiled module shadows a plain one
     * of the same name — and the *root* is the outer loop, so a module is resolved within one root
     * before the next root is considered.
     */
    fun fileFor(project: Project, module: String): VirtualFile? {
        val stem = module.trim().replace('.', '/')
        if (stem.isEmpty()) return null
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.toList() +
            ProjectRootManager.getInstance(project).contentRoots.toList() +
            listOfNotNull(project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) })
        return roots.firstNotNullOfOrNull { root ->
            BasedPythonSources.MODULE_EXTENSIONS.firstNotNullOfOrNull { extension ->
                root.findFileByRelativePath("$stem.$extension")
            }
        }
    }

    /**
     * The entry point [file] declares, read from the editor's copy when it has unsaved edits.
     *
     * Null for anything but a `.by`: a `main` is a command-line interface only where basedpython
     * generates the parser and the guard for it, and asking for the arguments of a plain `.py`'s
     * `main` would collect values nothing passes on. See [BasedPythonSources.hasGeneratedEntryPoint].
     */
    fun mainIn(project: Project, file: VirtualFile): ByMainFunction? {
        if (!BasedPythonSources.hasGeneratedEntryPoint(file)) return null
        return ReadAction.compute<ByMainFunction?, RuntimeException> {
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return@compute null
            mainIn(document)
        }
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
