package dev.basedpython.pycharm.editor.templates.macro

import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Shared, null-safe helpers for the basedpython live-template macros in this package.
 *
 * Everything here is self-contained and read-only with respect to the rest of the plugin: the
 * module-name and out-path logic mirrors (copies) the logic in
 * `dev.basedpython.pycharm.run.ByRunFromFileProducer.moduleNameFor` and
 * `dev.basedpython.pycharm.transpile.GoToGeneratedPyAction.resolveOutPath`, re-expressed against
 * an [ExpressionContext] (which exposes a project + editor rather than a ConfigurationContext).
 */
internal object ByMacroSupport {

    /** Resolve the `.by` (or any) [VirtualFile] for the editor backing the template [context]. */
    fun currentFile(context: ExpressionContext?): VirtualFile? {
        val editor = context?.editor ?: return null
        // Prefer the editor's bound virtual file; fall back via the document.
        editor.virtualFile?.let { return it }
        val document = editor.document
        return FileDocumentManager.getInstance().getFile(document)
    }

    /** The current project, if any. */
    fun project(context: ExpressionContext?): Project? = context?.project

    /** File name without its extension, e.g. `foo.by` -> `foo`. Empty when unavailable. */
    fun fileNameWithoutExtension(file: VirtualFile?): String = file?.nameWithoutExtension ?: ""

    /**
     * Dotted basedpython module path for [file], relative to its source/content root — the same
     * value the run-config "module" uses (e.g. `pkg/sub/foo.by` -> `pkg.sub.foo`).
     */
    fun moduleName(project: Project?, file: VirtualFile?): String {
        if (project == null || file == null) return ""
        val index = ProjectFileIndex.getInstance(project)
        val root = index.getSourceRootForFile(file)
            ?: index.getContentRootForFile(file)
            ?: ModuleUtilCore.findModuleForFile(file, project)?.let { m ->
                ModuleRootManager.getInstance(m).contentRoots.firstOrNull()
            }
            ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?: return ""
        val rel = VfsUtilCore.getRelativePath(file, root, '/') ?: return ""
        val noExt = rel.removeSuffix(".by")
        if (noExt.isBlank()) return ""
        return noExt.replace('/', '.')
    }

    /**
     * The `out/<rel>.py` path [file] transpiles to, relative to the file's own content root
     * (multi-root aware), using `/` separators (e.g. `out/pkg/sub/foo.py`). Falls back to the
     * project base path when the file is not under a registered content root. Empty when
     * unavailable.
     */
    fun outPath(project: Project?, file: VirtualFile?): String {
        if (project == null || file == null) return ""
        val index = ProjectFileIndex.getInstance(project)
        val base = index.getContentRootForFile(file)
            ?: index.getSourceRootForFile(file)
            ?: project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            ?: return ""
        val rel = VfsUtilCore.getRelativePath(file, base, '/') ?: return ""
        val withPyExt = rel.replaceFirst(Regex("\\.by$", RegexOption.IGNORE_CASE), ".py")
        return "out/$withPyExt"
    }

    /** Current system / login user, falling back to an empty string. */
    fun currentUser(): String = (System.getProperty("user.name") ?: "").trim()

    /** Today's date as `yyyy-MM-dd`. */
    fun today(): String = SimpleDateFormat("yyyy-MM-dd").format(Date())

    /**
     * A generated file-header comment line, e.g.
     * `# foo — generated 2026-05-29 by morgan`.
     */
    fun header(file: VirtualFile?): String {
        val name = fileNameWithoutExtension(file).ifEmpty { "file" }
        val user = currentUser()
        val by = if (user.isEmpty()) "" else " by $user"
        return "# $name — generated ${today()}$by"
    }
}
