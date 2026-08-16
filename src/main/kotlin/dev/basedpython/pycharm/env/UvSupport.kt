package dev.basedpython.pycharm.env

import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.env.manager.EnvTools
import dev.basedpython.pycharm.env.manager.UvBackend
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Locating uv and recognising a uv project, for the callers that are not the environment manager.
 *
 * Two of them: [ByEnvironments]'s `uv` environment source, and the debugpy installer. Both want the
 * same two answers the manager wants, so both get them from the same place — this used to do its own
 * `PATH` lookup, which stopped being the same answer the moment the plugin gained the ability to
 * install uv into a directory of its own.
 */
internal object UvSupport {

    /**
     * Locate a `uv` executable, or `null`.
     *
     * Delegates to [EnvTools], which is the plugin's single answer to "where is this tool": the
     * plugin's own install directory first, then `PATH`, then the directories uv's installers use.
     * A bare `PATH` lookup — what this was — would miss a uv the plugin itself had just installed,
     * so a run configuration pinned to the `uv` environment would report it missing immediately
     * after the environment window said it was installed.
     */
    fun findUv(): Path? = EnvTools.find(UvBackend)

    /** Project base path as a [Path], or `null`. */
    fun basePath(project: Project): Path? = project.basePath?.let { Paths.get(it) }

    /** True when a uv-managed project marker exists at the base. */
    fun hasProjectMarker(project: Project): Boolean {
        val base = basePath(project) ?: return false
        return UvBackend.projectMarkers.any { Files.isRegularFile(base.resolve(it)) }
    }
}
