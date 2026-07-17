package dev.basedpython.pycharm.run

import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.execution.ParametersListUtil
import java.nio.file.Path

/**
 * Shared `CommandLineState` for all `by` configurations. Subclasses provide subcommand-specific
 * positional args via [buildSubcommandArgs]; the base handles binary resolution, `--min-version`,
 * working dir, env vars, and process listener wiring.
 */
abstract class ByCommandLineState(
    protected val project: Project,
    protected val options: ByCommonOptions,
    environment: ExecutionEnvironment,
) : CommandLineState(environment) {

    /** Subcommand and its positional/flag arguments, e.g. `["run", "pkg.mod"]`. */
    protected abstract fun buildSubcommandArgs(): List<String>

    override fun startProcess(): ProcessHandler {
        val by: Path = BasedPythonBinaries.resolveBy(project)
            ?: throw ExecutionException("by binary not found — set path in Settings | basedpython")

        val cmd = GeneralCommandLine()
            .withExePath(by.toString())
            .withCharset(Charsets.UTF_8)

        if (options.pythonVersion.isNotBlank()) {
            cmd.addParameters("--min-version", options.pythonVersion.trim())
        }
        cmd.addParameters(buildSubcommandArgs())
        if (options.extraArgs.isNotBlank()) {
            cmd.addParameters(ParametersListUtil.parse(options.extraArgs))
        }

        val wd = options.workingDir.ifBlank { project.basePath ?: System.getProperty("user.home") }
        cmd.withWorkDirectory(FileUtil.toSystemDependentName(wd))

        cmd.withEnvironment(options.envVars)
        cmd.withParentEnvironmentType(
            if (options.passParentEnv) GeneralCommandLine.ParentEnvironmentType.CONSOLE
            else GeneralCommandLine.ParentEnvironmentType.NONE
        )

        val handler = KillableColoredProcessHandler(cmd)
        ProcessTerminatedListener.attach(handler)
        return handler
    }
}
