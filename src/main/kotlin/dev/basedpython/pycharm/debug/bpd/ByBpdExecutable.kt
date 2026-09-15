package dev.basedpython.pycharm.debug.bpd

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.env.Executables
import java.nio.file.Files
import java.nio.file.Path

/**
 * Where `bpd` is.
 *
 * Looked for beside `by` first, beside the interpreter second and on `PATH` last, which is the
 * order that matches how people install them: `uv add --dev basedpython` puts `by` in the
 * project's `.venv`, and a `bpd` installed the same way lands next to it. A `bpd` on `PATH` is the system-wide install, and is
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
    fun resolve(launch: ByLaunch?, python: Path): Path? {
        besideBy(launch)?.let { return it }
        beside(python)?.let { return it }
        return Executables.findOnPath(name())
    }

    /**
     * A `bpd` in the same directory as the `by` this run starts.
     *
     * That is the venv's `bin` (or `Scripts`) when `by` came from a venv, and wherever a
     * downloaded, bundled or configured `by` was put otherwise. Either way it is the toolchain this
     * project already chose.
     *
     * Not for a uv launch, whose executable is `uv` rather than `by`: what is beside `uv` is
     * whatever else was installed wherever uv was, which says nothing about this project.
     */
    private fun besideBy(launch: ByLaunch?): Path? {
        if (launch == null || launch.kind == ByEnvironmentKind.UV) return null
        return beside(launch.exe)
    }

    /**
     * A `bpd` beside the interpreter the program runs on: the environment's `bin`, which is where
     * `uv add --dev` puts it for a uv project, whose launch names no directory of its own.
     */
    private fun beside(executable: Path): Path? {
        val sibling = executable.parent?.resolve(name()) ?: return null
        return if (Files.isRegularFile(sibling)) sibling else null
    }
}
