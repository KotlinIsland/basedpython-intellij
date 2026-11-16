// Runs action ACTION as if invoked from the selected editor.
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    val action = ActionManager.getInstance().getAction("ACTION")!!
    val ctx = DataManager.getInstance().getDataContext(editor.contentComponent)
    ActionUtil.invokeAction(action, ctx, ActionPlaces.KEYBOARD_SHORTCUT, null, null)
    println("invoked ACTION")
}
