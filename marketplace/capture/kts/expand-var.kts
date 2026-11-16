// Expands the first top-level node named NAME in the Variables tree, and its children one level.
import com.intellij.openapi.application.EDT
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

repeat(2) {
withContext(Dispatchers.EDT) {
    val ui = XDebuggerManager.getInstance(project).currentSession!!.ui!!
    val trees = UIUtil.findComponentsOfType(ui.component, XDebuggerTree::class.java)
    val tree = trees.first { it.isShowing }
    for (i in 0 until tree.rowCount) {
        val label = tree.getPathForRow(i).lastPathComponent.toString()
        if (label.startsWith("NAME") || label.contains(" NAME ")) tree.expandRow(i)
    }
    for (i in 0 until tree.rowCount) println("row $i: ${tree.getPathForRow(i).lastPathComponent}")
}
kotlinx.coroutines.delay(1500)
}
