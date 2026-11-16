// Runs whatever run configuration the selected editor's caret produces from context (like the gutter).
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.ide.DataManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.fileEditor.FileEditorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

withContext(Dispatchers.EDT) {
    val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
    val ctx = ConfigurationContext.getFromContext(DataManager.getInstance().getDataContext(editor.contentComponent), "EditorGutter")
    val settings = ctx.configuration ?: error("no configuration from context")
    println("config: ${settings.name} (${settings.type.displayName})")
    ProgramRunnerUtil.executeConfiguration(settings, DefaultRunExecutor.getRunExecutorInstance())
}
