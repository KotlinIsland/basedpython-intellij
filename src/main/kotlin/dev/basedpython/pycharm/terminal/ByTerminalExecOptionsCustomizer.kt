package dev.basedpython.pycharm.terminal

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.eel.EelDescriptor
import com.intellij.platform.eel.path.EelPath
import com.intellij.platform.eel.provider.LocalEelDescriptor
import com.intellij.platform.eel.provider.asNioPath
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.BasedPythonSettings
import org.jetbrains.plugins.terminal.startup.MutableShellExecOptions
import org.jetbrains.plugins.terminal.startup.ShellExecOptionsCustomizer

/**
 * Starts a terminal's shell in a basedpython project with the project's `by` and `buff` on `PATH`,
 * as PyCharm's terminal activates a `.py` project's interpreter: `by run main` typed in the terminal
 * runs the `by` the editor's language server, Run and the debugger run.
 *
 * Before, a shell found whatever `by` the login shell's `PATH` held, if any: measured in PyCharm
 * 263.7607, `command -v by` in a project whose `by` the settings name answered nothing.
 *
 * Nothing is resolved here that the plugin does not already resolve: it is
 * [BasedPythonBinaries.launchBy] and [BasedPythonBinaries.launchBuff] for the directory the shell
 * starts in, turned into an activation by [ByEnvironments.toolchainActivation] — a venv-backed
 * toolchain's venv activated, a binary from outside one with its directory ahead of `PATH` — as the
 * git hooks' environment is ([dev.basedpython.pycharm.vcs.ByVcsEnvCustomizer]). A project that is not
 * basedpython's is left alone, as is a shell started on another machine (WSL, a container), where
 * these paths mean nothing.
 */
internal class ByTerminalExecOptionsCustomizer : ShellExecOptionsCustomizer {

    override fun customizeExecOptions(project: Project, shellExecOptions: MutableShellExecOptions) {
        val activation = activation(project, shellExecOptions.eelDescriptor, shellExecOptions.workingDirectory, shellExecOptions.envs["PATH"])
            ?: return
        for ((name, value) in activation.variables) shellExecOptions.setEnvironmentVariable(name, value)
        // each prepended ahead of the last, so the first ends up first
        for (entry in activation.path.asReversed()) shellExecOptions.prependEntryToPATH(entry)
    }

    companion object {
        /**
         * What a shell starting in [directory] of [project] on [eel], with [parentPath] as its
         * `PATH`, has to have to find the IDE's toolchain; null when it is left as it is.
         */
        fun activation(project: Project, eel: EelDescriptor, directory: EelPath, parentPath: String?): ByEnvironments.Activation? {
            if (project.isDisposed || project.isDefault) return null
            if (eel != LocalEelDescriptor) return null
            if (project.serviceOrNull<BasedPythonSettings>() == null) return null
            if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return null
            val launches = ReadAction.computeBlocking<List<ByLaunch>, RuntimeException> {
                if (project.isDisposed) return@computeBlocking emptyList()
                val root = LocalFileSystem.getInstance().findFileByNioFile(directory.asNioPath())
                listOfNotNull(BasedPythonBinaries.launchBy(project, root), BasedPythonBinaries.launchBuff(project, root))
            }
            return ByEnvironments.toolchainActivation(launches, parentPath)
        }
    }
}
