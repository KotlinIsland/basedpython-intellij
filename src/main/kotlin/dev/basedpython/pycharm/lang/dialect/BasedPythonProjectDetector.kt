package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cheap, side-effect-free detection of whether a [Project] should be treated as a
 * basedpython project (FEATURES.md §16).
 *
 * A project counts as basedpython when:
 *   1. the `by` LSP server is enabled in [BasedPythonSettings] (`byEnabled`), **and**
 *   2. the project carries a basedpython marker at its base path — either a project
 *      manifest (`pyproject.toml` / `api.lock`) or a top-level `.by` source file.
 *
 * The marker check is deliberately shallow: it inspects only the project base
 * directory (a single, bounded directory listing) so it never triggers indexing or
 * deep file-system walks. All file-system access is null-safe and swallows IO
 * errors (returning `false`), so the detector is safe to call from hot paths such as
 * a {@code FileTypeOverrider}.
 */
object BasedPythonProjectDetector {

    /** Manifest files that, when present at the base, mark a basedpython project. */
    private val MARKER_FILES: List<String> = listOf("pyproject.toml", "api.lock")

    /**
     * @return true when [project] looks like a basedpython project per the heuristic above.
     */
    fun isBasedPythonProject(project: Project): Boolean {
        if (!BasedPythonSettings.getInstance(project).byEnabled) return false
        val base = basePath(project) ?: return false
        return hasManifestMarker(base) || hasTopLevelByFile(base)
    }

    /** Project base path as a [Path], or null when the project has no base path. */
    private fun basePath(project: Project): Path? =
        project.basePath?.let {
            try {
                Paths.get(it)
            } catch (_: RuntimeException) {
                null
            }
        }

    /** True when a basedpython manifest file exists directly at [base]. */
    private fun hasManifestMarker(base: Path): Boolean =
        MARKER_FILES.any { name ->
            try {
                Files.isRegularFile(base.resolve(name))
            } catch (_: RuntimeException) {
                false
            }
        }

    /**
     * True when the base directory contains at least one top-level `.by` source file.
     * Only the base directory itself is scanned (no recursion) to keep the check cheap.
     */
    private fun hasTopLevelByFile(base: Path): Boolean {
        if (!safeIsDirectory(base)) return false
        return try {
            Files.newDirectoryStream(base, "*.by").use { stream ->
                stream.iterator().hasNext()
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeIsDirectory(path: Path): Boolean =
        try {
            Files.isDirectory(path)
        } catch (_: RuntimeException) {
            false
        }
}
