package dev.basedpython.pycharm.run

import com.intellij.execution.CantRunException
import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentAwareRunProfile
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.execution.target.getEffectiveConfiguration
import com.intellij.execution.target.getEffectiveTargetName
import com.intellij.openapi.project.Project
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.run.target.ByLanguageRuntimeType
import dev.basedpython.pycharm.util.BasedPythonBundle

/**
 * A `by` configuration that can be given a run target — Docker, SSH, WSL — in the platform's *Run on*
 * box: `by run`, the test configuration, `by build` and `by check`, each of which runs one `by`
 * command through [ByCommandLineState], which is what starts it there.
 *
 * What makes a configuration one is all here, so the four cannot disagree about it: which targets are
 * offered (those with a basedpython runtime, which says where the target's `by` is), where the choice
 * is stored, what is checked before a run, and which executors a target refuses. A configuration on a
 * target is run by [dev.basedpython.pycharm.run.target.ByTargetProgramRunner].
 */
abstract class ByTargetAwareConfiguration<O : ByCommonOptions>(project: Project, factory: ConfigurationFactory, name: String) :
    RunConfigurationBase<O>(project, factory, name), ByConfiguration, TargetEnvironmentAwareRunProfile {

    @Suppress("UNCHECKED_CAST") // each factory's options class is its configuration's [O]
    public override fun getOptions(): O = super.getOptions() as O

    /**
     * A target this configuration can run on: one with a basedpython runtime, which says where its
     * `by` is. The "Run on" box offers only these, and its "new target" wizard adds the runtime.
     */
    override fun canRunOn(target: TargetEnvironmentConfiguration): Boolean =
        ByLanguageRuntimeType.runtimeOf(target) != null

    override fun getDefaultLanguageRuntimeType(): LanguageRuntimeType<*> = ByLanguageRuntimeType.getInstance()

    /** Stored where the platform's own configurations keep it, `<target name="…"/>`. */
    override fun getDefaultTargetName(): String? = options.remoteTarget

    override fun setDefaultTargetName(targetName: String?) {
        options.remoteTarget = targetName
    }

    /** The run target this configuration runs on, or null for this machine. */
    val targetName: String? get() = getEffectiveTargetName(project)

    /**
     * That the `by` this configuration would run is there: on a target, that the target exists, is
     * valid and says where its `by` is; on this machine, that a local `by` is found.
     */
    protected fun checkBy() {
        if (targetName != null) {
            checkTarget()
        } else if (!BasedPythonBinaries.isByAvailable(project)) {
            throw RuntimeConfigurationException("by binary not found — set path in Settings | basedpython")
        }
    }

    /**
     * On a run target the `by` that matters is the target's, so whether one is installed here says
     * nothing: what has to hold is that the target exists, is valid, and says where its `by` is.
     *
     * The platform's validation checks each runtime the target has (so a basedpython runtime with no
     * `by` path is caught there) but not that it has *this* one: a target set up for Java alone
     * passes it.
     */
    private fun checkTarget() {
        validateRunTarget(project)
        val target = getEffectiveConfiguration(this, project) ?: return
        if (ByLanguageRuntimeType.runtimeOf(target) == null) {
            throw RuntimeConfigurationError(BasedPythonBundle.message("target.error.noRuntime", target.displayName))
        }
    }

    /**
     * Refuses any executor but Run and Debug on a target, before it has started anything to wait for
     * the run.
     *
     * Coverage needs a data file written here, which a target cannot write. Debug is let through:
     * [dev.basedpython.pycharm.debug.ByTargetDebugRunner] runs it there, with bpd dialling the IDE;
     * which backend can is the debugger's to say ([dev.basedpython.pycharm.debug.ByDapLaunchArgumentsProvider]).
     */
    protected fun refuseOnTarget(executor: Executor) {
        val target = targetName ?: return
        if (executor.id != DefaultRunExecutor.EXECUTOR_ID && executor.id != DefaultDebugExecutor.EXECUTOR_ID) {
            throw CantRunException(BasedPythonBundle.message("target.error.executor", executor.actionName, target))
        }
    }
}
