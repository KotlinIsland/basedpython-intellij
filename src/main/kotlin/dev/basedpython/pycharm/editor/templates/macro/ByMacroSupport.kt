package dev.basedpython.pycharm.editor.templates.macro

import com.intellij.codeInsight.template.ExpressionContext
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Shared, null-safe helpers for the basedpython live-template macros in this package.
 *
 * Read-only with respect to the rest of the plugin: the module-name logic mirrors (copies) the logic
 * in `dev.basedpython.pycharm.run.ByRunFromFileProducer.moduleNameFor`, re-expressed against an
 * [ExpressionContext] (which exposes a project + editor rather than a ConfigurationContext). The
 * output path is `by`'s answer, through [ByBuildOutputs].
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
     * The path `by build` writes [file] to, relative to its project root and `/`-separated (e.g.
     * `build/pkg/sub/foo.py` for `src/pkg/sub/foo.by`). Empty when `by` has not said.
     *
     * What `by` said when the file's editor opened, not a request: a macro is expanded on the EDT,
     * where no request may be made, and [ByBuildOutputs] asks for every `.by` file an editor opens.
     */
    fun outPath(project: Project?, file: VirtualFile?): String {
        if (project == null || file == null) return ""
        val answer = ByBuildOutputs.getInstance(project).cached(file) ?: return ""
        val root = answer.projectRoot ?: return ""
        val generated = answer.generated ?: return ""
        return FileUtil.getRelativePath(root, generated, '/') ?: ""
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
