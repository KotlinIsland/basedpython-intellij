package dev.basedpython.pycharm

import com.intellij.openapi.util.IconLoader
import dev.basedpython.pycharm.project.PyprojectIconProvider
import javax.swing.Icon

/**
 * The plugin's icons, in one place.
 *
 * `IconLoader.getIcon` caches per path, so the point is not sharing the instance but sharing the
 * *path*: it was written out at four call sites, and a renamed resource would have had to be found
 * at all of them.
 */
object BasedPythonIcons {
    /** The basedpython logo — file type, run configurations, project wizard. */
    @JvmField
    val Logo: Icon = IconLoader.getIcon("/icons/basedpython.svg", BasedPythonIcons::class.java)

    /**
     * A checklist — the hook/task view and the run configuration it produces.
     *
     * Not the logo: what this marks is not basedpython, it is whatever pre-commit, lefthook or
     * pyprojectx were told to run, and a row of `by` badges over a list of other tools' hooks would
     * claim them.
     */
    @JvmField
    val Tasks: Icon = IconLoader.getIcon("/icons/basedpythonTasks.svg", BasedPythonIcons::class.java)

    /**
     * The TOML icon with the Python logo in its notched bottom-right corner, which
     * [PyprojectIconProvider] hands to `pyproject.toml`.
     *
     * Drawn whole rather than layered at runtime, the way the platform draws its own composites,
     * and carrying JetBrains' own Python mark rather than a drawing of one — see the file for why,
     * and for what the copied geometry costs.
     */
    @JvmField
    val PyprojectToml: Icon =
        IconLoader.getIcon("/icons/pyprojectToml.svg", BasedPythonIcons::class.java)
}
