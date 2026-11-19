package dev.basedpython.pycharm.vcs

import com.intellij.openapi.components.serviceOrNull
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsEnvCustomizer
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.basedpython.pycharm.env.ByEnvironments
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
 *
 * It takes no lock. Git runs its customizers wherever a git command starts, including inside an
 * action's `update`, where locks are prohibited: reported from the Marketplace, another plugin's
 * toolbar widget asked for a repository there, the repository's first `git worktree list` came
 * through here, and the read action this took for the content-root lookup was reported as an error
 * four times in four seconds. So the `.venv` walk-up starts from the repository root, which is the
 * directory git runs the hooks in, rather than from its content root, which is an index query.
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
        val root = context.root?.toNioPathOrNull()
        val launches = listOfNotNull(
            BasedPythonBinaries.launchBy(project, searchFrom = root),
            BasedPythonBinaries.launchBuff(project, searchFrom = root),
        )
        envs.putAll(ByEnvironments.hookEnvironment(launches, envs["PATH"]))
    }
}
