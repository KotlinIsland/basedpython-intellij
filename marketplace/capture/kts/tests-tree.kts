// Shows passed tests in the selected run's test tree, expands it, and selects the first row whose
// text contains SELECT. Changing the filter rebuilds the tree asynchronously, so run this twice:
// the first pass sets the filter, the second expands the rebuilt tree.
import com.intellij.execution.testframework.TestConsoleProperties
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.util.ui.tree.TreeUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    // the Run window's own tab: RunContentManager.selectedContent can be a Debug tab instead
    val runTab = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("Run")!!.contentManager.selectedContent!!
    val view = com.intellij.execution.ui.RunContentManagerImpl.getRunContentDescriptorByContent(runTab)!!.executionConsole as SMTRunnerConsoleView
    val viewer = view.resultsViewer
    TestConsoleProperties.HIDE_PASSED_TESTS.set(viewer.properties, false)
    val tree = viewer.treeView!!
    TreeUtil.expandAll(tree)
    val target = (0 until tree.rowCount).firstOrNull { tree.getPathForRow(it).lastPathComponent.toString().contains("SELECT") }
    if (target != null) tree.setSelectionRow(target)
    println("rows: ${tree.rowCount}, selected: $target")
}
