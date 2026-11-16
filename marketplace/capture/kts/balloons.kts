import com.intellij.openapi.application.EDT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
withContext(Dispatchers.EDT) {
    val twm = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
    for (id in twm.toolWindowIds) twm.getToolWindowBalloon(id)?.let { println("balloon on $id"); it.hide() }
}
