package dev.basedpython.pycharm.run.target

import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.target.TargetEnvironmentAwareRunProfileState
import com.intellij.execution.target.getEffectiveTargetName
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import dev.basedpython.pycharm.run.ByRunConfiguration
import org.jetbrains.concurrency.Promise

/**
 * Runs a `by run` configuration on the run target it names.
 *
 * The platform's default Run runner executes a state on the EDT and never prepares a target, and
 * preparing one is slow — pulling or starting a container, connecting and uploading over SSH. So
 * this runner takes the configurations that have a target and drives the platform's target protocol
 * instead: [TargetEnvironmentAwareRunProfileState.prepareTargetToCommandExecution] prepares the
 * target off the EDT (showing its progress in the Build window, as the platform does for Java), then
 * starts the process on a background thread, and only the run tab is built on the EDT.
 *
 * A configuration left on the local machine never reaches this runner, so a local run is exactly
 * what it was before targets existed.
 */
class ByTargetProgramRunner : AsyncProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "basedpython.target"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultRunExecutor.EXECUTOR_ID &&
            profile is ByRunConfiguration &&
            profile.getEffectiveTargetName(profile.project) != null

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        // [canRun] admits only a `by run` configuration, whose state is a target-aware ByCommandLineState
        val targetState = checkNotNull(state as? TargetEnvironmentAwareRunProfileState) { "not a target-aware state: $state" }
        // a container mounts the project and an SSH target copies it, and both read it from disk
        FileDocumentManager.getInstance().saveAllDocuments()
        return targetState.prepareTargetToCommandExecution(environment, LOG, "Failed to run a by configuration on its target") {
            val result = state.execute(environment.executor, this) ?: return@prepareTargetToCommandExecution null
            var descriptor: RunContentDescriptor? = null
            ApplicationManager.getApplication().invokeAndWait {
                descriptor = RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
            }
            descriptor
        }
    }

    private companion object {
        val LOG = Logger.getInstance(ByTargetProgramRunner::class.java)
    }
}
