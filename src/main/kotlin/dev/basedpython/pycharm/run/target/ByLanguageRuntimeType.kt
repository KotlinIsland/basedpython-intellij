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
import dev.basedpython.pycharm.run.ByTargetAwareConfiguration
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
        runConfig.configuration is ByTargetAwareConfiguration<*>

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
        if (config.byPath.isNotBlank() && config.bpdPath.isNotBlank()) return null
        return object : Introspector<ByLanguageRuntimeConfiguration> {
            override fun introspect(subject: Introspectable): CompletableFuture<ByLanguageRuntimeConfiguration> =
                find(subject, "by").thenCompose { by ->
                    if (by != null && config.byPath.isBlank()) config.byPath = by
                    find(subject, "bpd")
                }.thenApply { bpd ->
                    if (bpd != null && config.bpdPath.isBlank()) config.bpdPath = bpd
                    config
                }
        }
    }

    /** Where the target's `PATH` finds [name], or null when it finds none. */
    private fun find(subject: Introspectable, name: String): CompletableFuture<String?> =
        subject.promiseExecuteScript(listOf("sh", "-c", "command -v $name"))
            .handle { output, _ ->
                output?.takeIf { it.exitCode == 0 }?.stdout?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotEmpty)
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

    /**
     * `bpd` on the target, for debugging there, as the target's shell would spell it; blank for the
     * one beside the interpreter `by run` chooses, which is where `pip install basedpython-debugger`
     * puts it.
     */
    var bpdPath: String = ""

    override fun getState(): State = State().also {
        it.byPath = byPath
        it.bpdPath = bpdPath
    }

    override fun loadState(state: State) {
        byPath = state.byPath.orEmpty()
        bpdPath = state.bpdPath.orEmpty()
    }

    override fun validateConfiguration() {
        super.validateConfiguration()
        if (byPath.isBlank()) {
            throw RuntimeConfigurationException(BasedPythonBundle.message("target.runtime.error.noBy"))
        }
    }

    class State : BaseState() {
        var byPath: String? by string()
        var bpdPath: String? by string()
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
        row(BasedPythonBundle.message("target.runtime.bpdPath")) {
            textField()
                .bindText(config::bpdPath)
                .align(AlignX.FILL)
                .comment(BasedPythonBundle.message("target.runtime.bpdPath.comment"))
        }
    }
}
