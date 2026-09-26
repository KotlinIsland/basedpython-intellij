package dev.basedpython.pycharm.settings.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import java.util.function.Predicate

/** The basedpython settings page, [BasedPythonConfigurable], as everything that links to it opens it. */
object BasedPythonSettingsPage {

    /** The `id` of the page's `projectConfigurable` in plugin.xml. */
    const val ID: String = "dev.basedpython.pycharm.settings"

    /**
     * Opens Settings on the basedpython page, selected by [ID].
     *
     * Not by name: the `String` overload of `showSettingsDialog` selects by display name, and
     * "basedpython" is also the display name of the colour settings page, which is the one it found
     * — the missing-binary banner's Configure… opened Editor | Color Scheme | basedpython. The two
     * callers that passed [ID] to that overload fared no better, since an id is not a display name.
     */
    fun show(project: Project?) {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            project,
            Predicate<Configurable> { (it as? SearchableConfigurable)?.id == ID },
            null,
        )
    }
}
