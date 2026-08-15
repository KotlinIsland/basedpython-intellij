package dev.basedpython.pycharm.debug.bpd

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import dev.basedpython.pycharm.env.ByLaunch
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where `bpd` is.
 *
 * Looked for beside `by` first and on `PATH` second, which is the order that matches how people
 * install them: `uv add --dev basedpython` puts `by` in the project's `.venv`, and a `bpd`
 * installed the same way lands next to it. A `bpd` on `PATH` is the system-wide install, and is
 * the right fallback rather than the first guess — a project pinned to one toolchain should not
 * silently borrow another one's debugger.
 */
object ByBpdExecutable {

    /** What the binary is called, per platform. */
    private fun name(): String = if (System.getProperty("os.name").lowercase().startsWith("windows")) {
        "bpd.exe"
    } else {
        "bpd"
    }

    /**
     * The `bpd` this project should use, or `null` when there is none.
     *
     * `null` rather than a throw: the caller is starting a debug session and has a much better
     * sentence to say about it than this does — it knows the user can switch backends instead.
     */
    fun resolve(launch: ByLaunch?): Path? {
        beside(launch)?.let { return it }
        return PathEnvironmentVariableUtil.findInPath(name())?.toPath()
    }

    /**
     * A `bpd` in the same directory as the `by` this project runs.
     *
     * That is the venv's `bin` (or `Scripts`) when `by` came from a venv, and wherever a
     * downloaded or bundled `by` was put otherwise. Either way it is the toolchain this project
     * already chose.
     */
    private fun beside(launch: ByLaunch?): Path? {
        val sibling = launch?.exe?.parent?.resolve(name()) ?: return null
        return if (Files.isRegularFile(sibling)) sibling else null
    }
}
