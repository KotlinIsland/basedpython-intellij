package dev.basedpython.pycharm.run.target

import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.configurations.RuntimeConfigurationException
import com.intellij.execution.target.LanguageRuntimeConfiguration
import com.intellij.execution.target.LanguageRuntimeType
import com.intellij.execution.target.TargetEnvironmentConfiguration
import com.intellij.execution.target.TargetEnvironmentType
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.run.ByRunConfiguration
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import javax.swing.Icon

/**
 * basedpython as a language a run target can carry: what a Docker image, SSH host or WSL
 * distribution needs to say before a `by` configuration can run on it, which is where its `by` is.
 *
 * The platform's run targets (Settings | Build, Execution, Deployment | Run Targets, and the "Run on"
 * box of a configuration that implements `TargetEnvironmentAwareRunProfile`) are made of a target type
 * — Docker, SSH, WSL, all bundled with the IDE — and one language runtime per language. This is the
 * runtime; [ByRunConfiguration] is the configuration that runs on it.
 *
 * Only the path of `by` is configured. The interpreter is `by run`'s to choose on the target as it is
 * locally, and the project's sources reach the target through the target's own project mapping (see
 * [ByTargetLaunch]), so neither is a field here.
 */
class ByLanguageRuntimeType : LanguageRuntimeType<ByLanguageRuntimeConfiguration>(TYPE_ID) {

    override val displayName: String = "basedpython"

    override val icon: Icon get() = BasedPythonIcons.Logo

    override val configurableDescription: String
        get() = BasedPythonBundle.message("target.runtime.configurableDescription")

    override val launchDescription: String
        get() = BasedPythonBundle.message("target.runtime.launchDescription")

    override fun isApplicableTo(runConfig: RunnerAndConfigurationSettings): Boolean =
        runConfig.configuration is ByRunConfiguration

    override fun createDefaultConfig(): ByLanguageRuntimeConfiguration = ByLanguageRuntimeConfiguration()

    override fun createSerializer(config: ByLanguageRuntimeConfiguration): PersistentStateComponent<*> = config

    override fun duplicateConfig(config: ByLanguageRuntimeConfiguration): ByLanguageRuntimeConfiguration =
        duplicatePersistentComponent(this, config)

    override fun findLanguageRuntime(target: TargetEnvironmentConfiguration): ByLanguageRuntimeConfiguration? =
        runtimeOf(target)

    override fun createConfigurable(
        project: Project,
        config: ByLanguageRuntimeConfiguration,
        targetEnvironmentType: TargetEnvironmentType<*>,
        targetSupplier: Supplier<TargetEnvironmentConfiguration>,
    ): Configurable = ByLanguageRuntimeConfigurable(config)

    /**
     * Fills in `by` from the target's `PATH` when the path is blank — which is what the target
     * wizard runs after it has connected, so an image or host that installed basedpython the usual
     * way (`pip install basedpython`, `uv tool install basedpython`) needs nothing typed.
     *
     * `command -v` rather than `which`: it is POSIX `sh`, and slim images ship no `which`.
     */
    override fun createIntrospector(config: ByLanguageRuntimeConfiguration): Introspector<ByLanguageRuntimeConfiguration>? {
        if (config.byPath.isNotBlank()) return null
        return object : Introspector<ByLanguageRuntimeConfiguration> {
            override fun introspect(subject: Introspectable): CompletableFuture<ByLanguageRuntimeConfiguration> =
                subject.promiseExecuteScript(listOf("sh", "-c", "command -v by"))
                    .handle { output, _ ->
                        val found = output?.takeIf { it.exitCode == 0 }?.stdout?.lineSequence()
                            ?.map(String::trim)?.firstOrNull(String::isNotEmpty)
                        if (found != null && config.byPath.isBlank()) config.byPath = found
                        config
                    }
        }
    }

    companion object {
        const val TYPE_ID: String = "BasedPythonLanguageRuntime"

        /** The basedpython runtime [target] carries, or null when it was set up for other languages only. */
        fun runtimeOf(target: TargetEnvironmentConfiguration): ByLanguageRuntimeConfiguration? =
            target.runtimes.findByType(ByLanguageRuntimeConfiguration::class.java)

        fun getInstance(): ByLanguageRuntimeType = EXTENSION_NAME.findExtensionOrFail(ByLanguageRuntimeType::class.java)
    }
}

/** A target's basedpython runtime: where `by` is on it. */
class ByLanguageRuntimeConfiguration :
    LanguageRuntimeConfiguration(ByLanguageRuntimeType.TYPE_ID),
    PersistentStateComponent<ByLanguageRuntimeConfiguration.State> {

    /**
     * `by` on the target, as the target's own shell would spell it: an absolute path, or a bare
     * `by` to have the target's `PATH` find it.
     */
    var byPath: String = ""

    override fun getState(): State = State().also { it.byPath = byPath }

    override fun loadState(state: State) {
        byPath = state.byPath.orEmpty()
    }

    override fun validateConfiguration() {
        super.validateConfiguration()
        if (byPath.isBlank()) {
            throw RuntimeConfigurationException(BasedPythonBundle.message("target.runtime.error.noBy"))
        }
    }

    class State : BaseState() {
        var byPath: String? by string()
    }
}

private class ByLanguageRuntimeConfigurable(private val config: ByLanguageRuntimeConfiguration) :
    BoundConfigurable(BasedPythonBundle.message("target.runtime.configurableDescription")) {

    override fun createPanel(): DialogPanel = panel {
        row(BasedPythonBundle.message("target.runtime.byPath")) {
            textField()
                .bindText(config::byPath)
                .align(AlignX.FILL)
                .comment(BasedPythonBundle.message("target.runtime.byPath.comment"))
        }
    }
}
