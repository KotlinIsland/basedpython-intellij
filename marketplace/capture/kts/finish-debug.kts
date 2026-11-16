import com.intellij.openapi.application.EDT
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
withContext(Dispatchers.EDT) {
    val bm = XDebuggerManager.getInstance(project).breakpointManager
    bm.allBreakpoints.filter { !bm.isDefaultBreakpoint(it) }.forEach { bm.removeBreakpoint(it) }
    XDebuggerManager.getInstance(project).currentSession?.resume()
}
kotlinx.coroutines.delay(8000)
println("sessions left: ${XDebuggerManager.getInstance(project).debugSessions.size}")
