package dev.basedpython.pycharm.project

import com.intellij.ide.FileIconProvider
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.basedpython.pycharm.BasedPythonIcons
import javax.swing.Icon

/**
 * Gives `pyproject.toml` the TOML icon with a Python chip in its bottom-right corner.
 *
 * A project's manifest is the file its owner opens most and the one they hunt for in a directory
 * of `.toml` — `ruff.toml`, `uv.toml`, `.pyprojectx.toml` and the manifest are four identical
 * blue T's in the project view, and the name is what has to be read to tell them apart.
 *
 * The icon is drawn whole rather than layered over the platform's TOML icon here; the reasoning
 * lives in `icons/pyprojectToml.svg`, next to the geometry it is about. What that leaves this
 * class is a name and a constant.
 *
 * `fileIconProvider` and not the other two icon extension points. `iconProvider` is asked about a
 * `PsiElement`, and `iconLayerProvider` about an `Iconable` — which is how the platform's own
 * source-root badge is registered — so both answer in the project view and nowhere else. This one
 * is asked about a [VirtualFile], and both paths reach it: `IconUtil.computeFileIcon` consults it
 * directly for editor tabs, Search Everywhere, breadcrumbs and the file choosers, and
 * `ElementBase.doComputeIconNow` falls through to it for a `PsiFileSystemItem` once the
 * `IconProvider`s have declined. One registration, every surface.
 */
class PyprojectIconProvider : FileIconProvider {

    override fun getIcon(file: VirtualFile, flags: Int, project: Project?): Icon? =
        BasedPythonIcons.PyprojectToml.takeIf { !file.isDirectory && file.name == MANIFEST }

    private companion object {
        const val MANIFEST = "pyproject.toml"
    }
}
