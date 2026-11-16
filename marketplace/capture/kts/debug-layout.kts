// Brings the Threads & Variables tab forward, sizes the Debug window, and scrolls the editor to the frame.
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowEx
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val s = XDebuggerManager.getInstance(project).currentSession!!
    val ui = s.ui!!
    ui.contentManager.contents.forEach { println("content: ${it.displayName} / ${it.tabName}") }
    ui.contentManager.contents.firstOrNull { it.displayName?.contains("Variables") == true || it.tabName?.contains("Variables") == true }
        ?.let { ui.contentManager.setSelectedContent(it) }
    val tw = ToolWindowManager.getInstance(project).getToolWindow("Debug")!! as ToolWindowEx
    tw.stretchHeight((tw.component.rootPane.height * RATIO).toInt() - tw.component.height)
}
kotlinx.coroutines.delay(800)
withContext(Dispatchers.EDT) {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    println("editor: ${editor.virtualFile?.name}")
    editor.scrollingModel.disableAnimation()
    editor.scrollingModel.scrollVertically(editor.logicalPositionToXY(LogicalPosition(TOP - 1, 0)).y)
}
