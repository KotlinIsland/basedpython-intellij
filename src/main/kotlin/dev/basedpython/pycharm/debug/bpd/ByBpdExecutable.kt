package dev.basedpython.pycharm.debug.bpd

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.env.Executables
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `bpd`s the IDE can find before the run starts.
 *
 * `bpd` is looked for beside `by` first, beside the interpreter second and on `PATH` last, which is
 * the order that matches how people install them: `uv add --dev basedpython` puts `by` in the
 * project's `.venv`, and a `bpd` installed the same way lands next to it. A `bpd` on `PATH` is the
 * system-wide install, and is the right fallback rather than the first guess — a project pinned to
 * one toolchain should not silently borrow another one's debugger.
 *
 * The middle one is not here. The interpreter is the one `by run` chooses, and only the wrapper
 * `by run` starts is told which — so the wrapper applies the order, with these two as its first and
 * last answers. See [ByBpdWrapper].
 */
object ByBpdExecutable {

    /** The two `bpd`s the IDE can find itself: beside `by`, and on `PATH`. Either may be absent. */
    data class Found(val besideBy: Path?, val onPath: Path?)

    /** What the binary is called, per platform. */
    private fun name(): String = if (System.getProperty("os.name").lowercase().startsWith("windows")) {
        "bpd.exe"
    } else {
        "bpd"
    }

    /** The `bpd` beside the `by` [launch] starts, and the one on `PATH`. */
    fun find(launch: ByLaunch?): Found = Found(besideBy(launch), Executables.findOnPath(name()))

    /**
     * A `bpd` in the same directory as the `by` this run starts.
     *
     * That is the venv's `bin` (or `Scripts`) when `by` came from a venv, and wherever a
     * downloaded, bundled or configured `by` was put otherwise. Either way it is the toolchain this
     * project already chose.
     *
     * Not for a uv launch, whose executable is `uv` rather than `by`: what is beside `uv` is
     * whatever else was installed wherever uv was, which says nothing about this project. Its `bpd`
     * is the one in the environment the program runs on, which the wrapper finds beside the
     * interpreter.
     */
    private fun besideBy(launch: ByLaunch?): Path? {
        if (launch == null || launch.kind == ByEnvironmentKind.UV) return null
        val sibling = launch.exe.parent?.resolve(name()) ?: return null
        return if (Files.isRegularFile(sibling)) sibling else null
    }
}
