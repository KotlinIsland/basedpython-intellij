package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory

internal class BasedPythonStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = BasedPythonStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = "BasedPython LSP"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = BasedPythonStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) { com.intellij.openapi.util.Disposer.dispose(widget) }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
