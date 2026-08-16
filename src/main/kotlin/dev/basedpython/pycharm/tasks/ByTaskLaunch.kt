package dev.basedpython.pycharm.tasks

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByEnvironments
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.env.Executables
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Where a task runner's executable comes from.
 *
 * Three of the four are ordinary tools, and go through [ByEnvironments.resolve] exactly as `by` and
 * `buff` do: the project's `.venv` first, then the venv behind a configured interpreter, then
 * `PATH`. That order is the point — `pre-commit` is nearly always a development dependency of the
 * project it checks, and a globally installed one of a different version would otherwise shadow it.
 *
 * pyprojectx is the exception, and by design: its whole proposition is that a clone needs nothing
 * installed, so the thing to run is the `pw` wrapper checked into the repository.
 */
internal object ByTaskLaunch {

    /** The launch for [runner], or null when nothing on this machine can run it. */
    fun resolve(project: Project, runner: ByTaskRunner): ByLaunch? = when (runner) {
        ByTaskRunner.PYPROJECTX -> pyprojectx(project)
        else -> ByEnvironments.resolve(project, runner.binary)
    }

    /** True when [runner] can be run here. Used to decide which of the two pre-commits to offer. */
    fun isAvailable(project: Project, runner: ByTaskRunner): Boolean = resolve(project, runner) != null

    /**
     * The wrapper script, or a globally installed pyprojectx.
     *
     * `pw` is checked in and executable in every project that uses it — but a fresh clone on a
     * filesystem that dropped the mode bit, or a checkout made by a tool that did, leaves a file
     * that is perfectly good and one `chmod` away from running. That is the same situation the
     * downloaded and bundled binaries are in, and it is fixed the same way rather than reported as
     * "pyprojectx not found" beside a `pw` the user can plainly see.
     */
    private fun pyprojectx(project: Project): ByLaunch? {
        val base = project.basePath?.let { Paths.get(it) }
        if (base != null) {
            for (name in wrapperNames()) {
                val wrapper = base.resolve(name)
                if (!Files.isRegularFile(wrapper)) continue
                if (Files.isExecutable(wrapper) || Executables.makeExecutable(wrapper)) {
                    return ByLaunch(wrapper, emptyList(), emptyMap(), null, ByEnvironmentKind.PATH)
                }
            }
        }
        // No wrapper: pyprojectx installs `px`, which finds the project's own pyproject.toml.
        return listOf("px", "pyprojectx").firstNotNullOfOrNull { onPath(it) }
    }

    private fun wrapperNames(): List<String> = if (SystemInfo.isWindows) listOf("pw.bat", "pw") else listOf("pw")

    private fun onPath(name: String): ByLaunch? {
        val exe: Path = PathEnvironmentVariableUtil
            .findInPath(if (SystemInfo.isWindows) "$name.exe" else name)
            ?.toPath()
            ?: return null
        return ByLaunch(exe, emptyList(), emptyMap(), null, ByEnvironmentKind.PATH)
    }
}
