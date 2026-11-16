import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Splitter
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val tw = ToolWindowManager.getInstance(project).getToolWindow("Run")!! as ToolWindowEx
    val target = (tw.component.rootPane.height * RATIO).toInt()
    tw.stretchHeight(target - tw.component.height)
    // the Run window's own tab: RunContentManager.selectedContent can be a Debug tab instead
    val runTab = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Run")!!.contentManager.selectedContent!!
    val view = com.intellij.execution.ui.RunContentManagerImpl.getRunContentDescriptorByContent(runTab)!!.executionConsole as SMTRunnerConsoleView
    val splitters = UIUtil.findComponentsOfType(view.component, Splitter::class.java)
    splitters.forEach { println("splitter ${it.javaClass.simpleName} vertical=${it.isVertical} p=${it.proportion} w=${it.width}") }
    splitters.firstOrNull { !it.isVertical }?.proportion = PROP
}
kotlinx.coroutines.delay(800)
withContext(Dispatchers.EDT) {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    editor.scrollingModel.disableAnimation()
    editor.scrollingModel.scrollVertically(editor.logicalPositionToXY(LogicalPosition(TOP - 1, 0)).y)
    println("run height ${ToolWindowManager.getInstance(project).getToolWindow("Run")!!.component.height}")
}
