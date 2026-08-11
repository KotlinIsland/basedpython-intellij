package dev.basedpython.pycharm

import com.intellij.openapi.util.IconLoader
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
}
