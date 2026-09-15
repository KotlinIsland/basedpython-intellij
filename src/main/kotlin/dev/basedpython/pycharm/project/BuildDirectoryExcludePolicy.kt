package dev.basedpython.pycharm.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.impl.DirectoryIndexExcludePolicy
import com.intellij.openapi.vfs.VfsUtilCore
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Excludes the directories `by build` writes to from IDE indexing.
 *
 * Without this, the `.py` files a build generates pollute search results, the "Go to Class" index,
 * and Find Usages — all of which should operate on the `.by` source files instead.
 *
 * Which directories is `by`'s answer, one per project root, kept by [ByBuildOutputs] — so a project
 * `by` has never answered for excludes nothing. That is the scoping: a Rust or JS project, where no
 * `by` server runs, is left exactly as the platform found it, where this used to exclude an `out/`
 * under every content root of every project the IDE opened.
 *
 * IntelliJ accepts future-URL exclusions, so a build directory is excluded before its first build.
 *
 * Opt-out: when [BasedPythonSettings.indexGeneratedPython] is enabled the user wants a Python plugin
 * to index the generated `.py`, so nothing is excluded.
 */
class BuildDirectoryExcludePolicy(private val project: Project) : DirectoryIndexExcludePolicy {

    override fun getExcludeUrlsForProject(): Array<String> {
        if (BasedPythonSettings.getInstance(project).indexGeneratedPython) return emptyArray()
        return ByBuildOutputs.getInstance(project).buildDirectories
            .map(VfsUtilCore::pathToUrl)
            .toTypedArray()
    }
}
