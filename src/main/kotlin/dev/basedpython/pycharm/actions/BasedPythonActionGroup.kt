package dev.basedpython.pycharm.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.DefaultActionGroup

/** "basedpython" submenu under Tools. Children declared in plugin.xml. */
class BasedPythonActionGroup : DefaultActionGroup() {
    init {
        templatePresentation.icon = AllIcons.Nodes.Plugin
        templatePresentation.text = "basedpython"
        isPopup = true
    }
}
