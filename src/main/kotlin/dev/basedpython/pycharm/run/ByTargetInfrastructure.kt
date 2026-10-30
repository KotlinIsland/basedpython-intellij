package dev.basedpython.pycharm.run

import com.intellij.execution.target.TargetEnvironment
import com.intellij.execution.target.TargetEnvironmentRequest
import com.intellij.execution.target.TargetProgressIndicator
import dev.basedpython.pycharm.run.target.ByTargetPaths

/**
 * What something running alongside a `by` command — a debugger — needs of the run target the
 * command runs on, and adds to it.
 *
 * The run-target counterpart of [ByCommandLineState.infrastructureEnv] and
 * [ByCommandLineState.infrastructureArgs]: those name files and ports on this machine, which a
 * target has none of, so a run on a target refuses them. This asks for what it needs while the
 * target is being requested ([prepare]) and says, once it exists ([created]), what the command line
 * gets in the target's own terms ([arguments], [environment]).
 */
internal interface ByTargetInfrastructure {

    /** Adds what it needs to the request for the target: volumes, port bindings. */
    fun prepare(request: TargetEnvironmentRequest)

    /** The target exists; [paths] is where the project is on it. */
    fun created(environment: TargetEnvironment, progress: TargetProgressIndicator, paths: ByTargetPaths)

    /** Flags for `by`, in front of the positionals as [ByCommandLineState.infrastructureArgs] are. */
    fun arguments(): List<String>

    /** Variables for `by`, in the target's terms. */
    fun environment(): Map<String, String>
}
