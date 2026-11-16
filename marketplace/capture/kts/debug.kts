// Puts a line breakpoint at BP_FILE:BP_LINE, then debugs the configuration the selected editor's context gives.
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.ide.DataManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val bm = XDebuggerManager.getInstance(project).breakpointManager
    bm.allBreakpoints.filter { !bm.isDefaultBreakpoint(it) }.forEach { bm.removeBreakpoint(it) }
    val vf = LocalFileSystem.getInstance().findFileByPath("${project.basePath}/BP_FILE")!!
    XDebuggerUtil.getInstance().toggleLineBreakpoint(project, vf, BP_LINE - 1)
    println("breakpoints: ${bm.allBreakpoints.map { it.javaClass.simpleName + " " + (it.sourcePosition?.line?.plus(1)) }}")
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    val ctx = ConfigurationContext.getFromContext(DataManager.getInstance().getDataContext(editor.contentComponent), "EditorGutter")
    val settings = ctx.configuration!!
    println("debugging ${settings.name}")
    ProgramRunnerUtil.executeConfiguration(settings, DefaultDebugExecutor.getDebugExecutorInstance())
}
