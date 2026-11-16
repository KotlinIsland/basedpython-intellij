import com.intellij.xdebugger.XDebuggerManager
val s = XDebuggerManager.getInstance(project).currentSession
println("session=${s?.sessionName} suspended=${s?.isSuspended} pos=${s?.currentPosition?.file?.name}:${s?.currentPosition?.line?.plus(1)}")
