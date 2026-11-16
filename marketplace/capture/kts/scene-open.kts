// Hides every tool window, opens FILE with the caret at LINE:COL, optionally shows TOOLWINDOW.
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val twm = ToolWindowManager.getInstance(project)
    for (id in twm.toolWindowIds) twm.getToolWindow(id)?.takeIf { it.isVisible }?.hide()
    val fem = FileEditorManager.getInstance(project)
    fem.openFiles.forEach { fem.closeFile(it) }
    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath("${project.basePath}/FILE")!!
    fem.openTextEditor(OpenFileDescriptor(project, vf, LINE - 1, COL - 1), true)
    "TOOLWINDOW".takeIf { it.isNotEmpty() }?.let { twm.getToolWindow(it)?.show() }
    println("opened ${vf.path}")
}
