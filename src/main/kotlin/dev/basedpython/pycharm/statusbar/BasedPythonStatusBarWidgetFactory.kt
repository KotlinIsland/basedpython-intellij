package dev.basedpython.pycharm.statusbar

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector

internal class BasedPythonStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = BasedPythonStatusBarWidget.WIDGET_ID
    override fun getDisplayName(): String = "basedpython LSP"
    // A project with no Python in it has no language servers to report on.
    override fun isAvailable(project: Project): Boolean =
        BasedPythonProjectDetector.isPythonProject(project)
    override fun createWidget(project: Project): StatusBarWidget = BasedPythonStatusBarWidget(project)
    override fun disposeWidget(widget: StatusBarWidget) { com.intellij.openapi.util.Disposer.dispose(widget) }
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}
