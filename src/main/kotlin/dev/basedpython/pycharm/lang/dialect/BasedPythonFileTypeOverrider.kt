package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.impl.FileTypeOverrider
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.lang.BasedPythonFileType

/**
 * Treats plain `.py` files inside a basedpython project as BasedPython source
 * (FEATURES.md §16).
 *
 * Rationale: the IDE target ships **no** bundled Python plugin, so a `.py` file in a
 * basedpython project would otherwise open as plain text — no highlighting, no LSP.
 * Re-typing such files to [BasedPythonFileType] gives them basedpython highlighting and
 * routes them through the `by` / `buff` LSP servers (whose supported extensions already
 * include `py`).
 *
 * Scope is intentionally narrow:
 *   - Only `.py` is overridden. `.by` is already handled by the file-type registration,
 *     and `.pyi` stub files are left alone.
 *   - Only files that resolve to a basedpython project (see [BasedPythonProjectDetector])
 *     are overridden; in a vanilla project `.py` is left untouched.
 *
 * Resolving a {@code Project} from a {@code VirtualFile} here is best-effort via
 * [ProjectLocator.guessProjectForFile]; a null project means "don't override".
 */
class BasedPythonFileTypeOverrider : FileTypeOverrider {

    override fun getOverriddenFileType(file: VirtualFile): FileType? {
        // Cheap extension gate first — bail before touching project services.
        if (!isOverridableExtension(file.extension)) return null
        val project = ProjectLocator.getInstance().guessProjectForFile(file) ?: return null
        return decide(
            extension = file.extension,
            isBasedPythonProject = BasedPythonProjectDetector.isBasedPythonProject(project),
        )
    }

    companion object {
        /** Extension we are willing to override (case-insensitive); only plain `.py`. */
        const val OVERRIDABLE_EXTENSION: String = "py"

        /** True when [extension] is the one extension this overrider acts on. */
        fun isOverridableExtension(extension: String?): Boolean =
            extension?.lowercase() == OVERRIDABLE_EXTENSION

        /**
         * Pure decision used by [getOverriddenFileType], extracted for unit testing.
         *
         * @return [BasedPythonFileType.INSTANCE] when [extension] is `py` (case-insensitive)
         *   and [isBasedPythonProject] is true; otherwise null (normal handling applies).
         */
        fun decide(extension: String?, isBasedPythonProject: Boolean): FileType? {
            if (!isOverridableExtension(extension)) return null
            if (!isBasedPythonProject) return null
            return BasedPythonFileType.INSTANCE
        }
    }
}
