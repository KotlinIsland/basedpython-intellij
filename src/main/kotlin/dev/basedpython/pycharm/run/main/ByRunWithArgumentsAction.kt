package dev.basedpython.pycharm.run.main

import com.intellij.execution.Location
import com.intellij.execution.RunManager
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.RunConfigurationProducer
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.ExecutionUtil
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.run.ByRunFromFileProducer

/**
 * Asks for [main]'s arguments, and writes what the user gave to [configuration].
 *
 * The values are also remembered against the module, which is what makes this a once-per-program
 * question: [ByRunFromFileProducer] seeds the next context configuration from that memory, so plain
 * Run keeps running the program the way it was last run.
 *
 * @return how the run was asked to start, or null when the dialog was cancelled
 */
internal fun promptForArguments(
    project: Project,
    configuration: ByRunConfiguration,
    main: ByMainFunction,
    start: String? = null,
): ByMainArgumentsDialog.Result? {
    val module = configuration.options.module
    val initial = configuration.options.programArgs
        .ifBlank { ByMainArgumentHistory.last(project, module).orEmpty() }
    val dialog = ByMainArgumentsDialog(project, module, main, initial, start)
    if (!dialog.showAndGet()) return null
    val result = dialog.result()
    configuration.options.programArgs = result.arguments
    ByMainArgumentHistory.remember(project, module, result.arguments)
    return result
}

/**
 * The gutter's second run action: fill in `main`'s parameters, then run.
 *
 * Offered next to the ordinary Run and Debug on a `def main` that takes arguments — first in the
 * list while the run would otherwise fail for want of a required one. It is not registered in
 * `plugin.xml`: [dev.basedpython.pycharm.run.marker.ByRunLineMarkerContributor] hands it to the
 * gutter directly, which is the only place it belongs.
 */
internal class ByRunWithArgumentsAction : AnAction(TEXT, DESCRIPTION, AllIcons.Actions.Execute) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = mainAt(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val main = mainAt(e) ?: return
        val context = ConfigurationContext.getFromContext(e.dataContext, e.place)
        val producer = RunConfigurationProducer.getInstance(ByRunFromFileProducer::class.java)
        val settings = producer.findOrCreateConfigurationFromContext(context)?.configurationSettings ?: return
        val configuration = settings.configuration as? ByRunConfiguration ?: return

        val result = promptForArguments(project, configuration, main) ?: return

        // A configuration this action just invented has to be registered before it can be run, the
        // same way the platform's own context-run actions register theirs.
        val runManager = RunManager.getInstance(project)
        if (runManager.findSettings(settings.configuration) == null) {
            runManager.setTemporaryConfiguration(settings)
        }
        runManager.selectedConfiguration = settings
        val executor =
            if (result.debug) DefaultDebugExecutor.getDebugExecutorInstance()
            else DefaultRunExecutor.getRunExecutorInstance()
        ExecutionUtil.runConfiguration(settings, executor)
    }

    /**
     * The entry point of the file the action was invoked over, when it has arguments to fill.
     *
     * [Location.DATA_KEY] first: the gutter wraps every action it is given in a
     * `LineMarkerActionWrapper`, whose whole job is to put the marked element's location in the
     * data context — the editor's own `PSI_FILE` is the fallback for every other place.
     */
    private fun mainAt(e: AnActionEvent): ByMainFunction? {
        val project = e.project ?: return null
        val file = e.getData(Location.DATA_KEY)?.psiElement?.containingFile
            ?: e.getData(CommonDataKeys.PSI_FILE)
            ?: return null
        if (file.virtualFile?.extension != BY_EXTENSION) return null
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        return ByMainModules.mainIn(document)?.takeIf { it.takesArguments }
    }

    private companion object {
        const val TEXT = "Run with Arguments…"
        const val DESCRIPTION = "Fill in main's parameters, then run"
        const val BY_EXTENSION = "by"
    }
}
