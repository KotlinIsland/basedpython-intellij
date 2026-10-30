package dev.basedpython.pycharm.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.AsyncProgramRunner
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.target.TargetEnvironmentAwareRunProfileState
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.dap.DapProcessStarter
import com.intellij.xdebugger.XDebuggerManager
import dev.basedpython.pycharm.run.ByCommandLineState
import dev.basedpython.pycharm.run.ByTargetAwareConfiguration
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.jetbrains.concurrency.Promise

/**
 * Debugs a `by run` or test configuration on the run target it names, with bpd.
 *
 * The platform's DAP runner never prepares a target: it executes the state inside the debug
 * session's start, so a target would be prepared there, on the EDT, or not at all. So this runner
 * takes the target's sessions first, as [dev.basedpython.pycharm.run.target.ByTargetProgramRunner]
 * does for Run: the session is prepared ([ByDapLaunchArgumentsProvider], whose [ByDebugSetup] for a
 * target is a [dev.basedpython.pycharm.debug.bpd.ByBpdOnTarget]), handed to the state as what the
 * target must carry, the target is prepared off the EDT, and only then is the debug session started
 * through the platform's own [DapProcessStarter] — which executes the state, as for a session here.
 */
class ByTargetDebugRunner : AsyncProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "basedpython.target.debug"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID &&
            profile is ByTargetAwareConfiguration<*> &&
            profile.targetName != null &&
            ByDapLaunchArgumentsProvider().isApplicable(executorId, profile)

    override fun execute(environment: ExecutionEnvironment, state: RunProfileState): Promise<RunContentDescriptor?> {
        val project = environment.project
        val profile = environment.runProfile
        val commandLine = state as? ByCommandLineState
            ?: throw ExecutionException(BasedPythonBundle.message("debug.error.unsupportedState"))
        val arguments = ByDapLaunchArgumentsProvider().getLaunchArguments(project, profile)
        val onTarget = ByDebugSetups.getInstance(project).peek(profile)?.onTarget
            ?: throw ExecutionException(BasedPythonBundle.message("debug.error.noSetup"))
        commandLine.targetInfrastructure = onTarget
        // a container mounts the project and an SSH target copies it, and both read it from disk
        FileDocumentManager.getInstance().saveAllDocuments()
        val targetState = commandLine as TargetEnvironmentAwareRunProfileState
        return targetState.prepareTargetToCommandExecution(environment, LOG, "Failed to debug a by configuration on its target") {
            var descriptor: RunContentDescriptor? = null
            ApplicationManager.getApplication().invokeAndWait {
                val starter = DapProcessStarter(
                    environment,
                    environment.executor,
                    state,
                    arguments.adapterId,
                    arguments.request,
                    arguments.arguments,
                )
                descriptor = XDebuggerManager.getInstance(project).newSessionBuilder(starter)
                    .environment(environment)
                    .startSession()
                    .runContentDescriptor
            }
            descriptor
        }
    }

    private companion object {
        val LOG = Logger.getInstance(ByTargetDebugRunner::class.java)
    }
}
