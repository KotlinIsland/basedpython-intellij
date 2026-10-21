package dev.basedpython.pycharm.vcs

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsEnvCustomizer
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Runs git with the project's `by` and `buff` on `PATH`, so a pre-commit or prek hook that calls
 * them finds the ones the editor uses.
 *
 * A commit from the IDE runs its hooks in the IDE's environment, which for an IDE started from the
 * Dock is the login shell's at best and never a project's venv: `by check` in a hook failed with
 * *command not found* on every commit made from the commit dialog, and passed from a terminal with
 * the venv activated. The platform calls this for every git command it runs, with the environment
 * it is about to run it in.
 *
 * Nothing is resolved here that the plugin does not already resolve: it is
 * [BasedPythonBinaries.launchBy] and [BasedPythonBinaries.launchBuff] for the repository root,
 * exactly as the language servers are started, turned into an environment by
 * [ByEnvironments.hookEnvironment]. A project that is not basedpython's is left alone, as is a git
 * run under WSL, whose hooks run in Linux where these paths mean nothing.
 *
 * Every git command the IDE runs passes through here — status refreshes, log loads — so this is
 * kept to the lookups resolution already does: a few file checks and the cached project verdict.
 * The content-root lookup inside resolution reads the project model, so it is done in a read
 * action; the platform only asks with read locks allowed (`GitHandler` skips the customizers where
 * they are not).
 */
internal class ByVcsEnvCustomizer : VcsEnvCustomizer() {

    override fun customizeCommandAndEnvironment(
        project: Project?,
        envs: MutableMap<String, String>,
        context: VcsExecutableContext,
    ) {
        if (project == null || project.isDisposed || project.isDefault) return
        if (context.type != ExecutableType.LOCAL) return
        // A git command can start while this plugin is being unloaded, and its services are then
        // already gone: measured, a status refresh during the unload reached here and the settings
        // lookup threw. There is nothing to add to a command then.
        if (project.serviceOrNull<BasedPythonSettings>() == null) return
        if (!BasedPythonProjectDetector.isBasedPythonProject(project)) return
        val root = context.root
        val launches = ReadAction.computeBlocking<List<ByLaunch>, RuntimeException> {
            if (project.isDisposed) return@computeBlocking emptyList()
            listOfNotNull(BasedPythonBinaries.launchBy(project, root), BasedPythonBinaries.launchBuff(project, root))
        }
        envs.putAll(ByEnvironments.hookEnvironment(launches, envs["PATH"]))
    }
}
