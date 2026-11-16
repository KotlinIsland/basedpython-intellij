// Puts the sandbox into a repeatable state for captures: 1280x720 frame, Islands Dark, no stray tool windows.
import com.intellij.ide.ui.LafManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val frame = WindowManager.getInstance().getFrame(project)!!
    frame.extendedState = java.awt.Frame.NORMAL
    frame.setSize(1280, 720 + (frame.height - frame.rootPane.height))
    frame.setLocation(40, 40)
    val laf = LafManager.getInstance()
    println("theme: ${laf.currentUIThemeLookAndFeel?.name}; themes: ${laf.installedThemes.map { it.name }.toList()}")
    val twm = ToolWindowManager.getInstance(project)
    println("tool windows: ${twm.toolWindowIds.toList()}")
    frame.validate()
    println("root ${frame.rootPane.width}x${frame.rootPane.height}")
}
