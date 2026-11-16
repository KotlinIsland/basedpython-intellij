// Returns the IDE to a clean slate between scenes: stops debug sessions, closes every Run and Debug
// tab (so a later scene never picks up an earlier one's), and expires notifications and balloons.
import com.intellij.execution.ui.RunContentManager
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    XDebuggerManager.getInstance(project).debugSessions.forEach { it.stop() }
}
kotlinx.coroutines.delay(1500)
withContext(Dispatchers.EDT) {
    for (d in RunContentManager.getInstance(project).allDescriptors) {
        d.processHandler?.takeIf { !it.isProcessTerminated }?.destroyProcess()
        d.attachedContent?.let { c -> c.manager?.removeContent(c, true) }
    }
    val twm = ToolWindowManager.getInstance(project)
    for (id in twm.toolWindowIds) twm.getToolWindowBalloon(id)?.hide()
    NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project).forEach { it.expire() }
    println("clean: ${RunContentManager.getInstance(project).allDescriptors.size} run tabs left")
}
