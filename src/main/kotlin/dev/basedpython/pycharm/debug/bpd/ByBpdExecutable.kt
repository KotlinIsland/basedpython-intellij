package dev.basedpython.pycharm.debug.bpd

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import dev.basedpython.pycharm.env.Executables
import dev.basedpython.pycharm.env.bundled.BundledBinaries
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `bpd`s the IDE can find before the run starts.
 *
 * `bpd` is looked for beside `by` first, beside the interpreter second, then the one bundled with
 * the plugin, and on `PATH` last. The first two match how people install them: `uv add --dev
 * basedpython` puts `by` in the project's `.venv`, and a `bpd` installed the same way lands next to
 * it. Bundled before `PATH` is the order `by` itself is resolved in, and what the docs promise — the
 * plugin carries its own `bpd`, so a project that installs only `basedpython` can still debug. A
 * `bpd` on `PATH` is the system-wide install, and the last resort rather than the first guess — a
 * project pinned to one toolchain should not silently borrow another one's debugger.
 *
 * The bundled one is a fallback and not a refusal because nothing else keeps `bpd` in step with
 * `by` either: one on `PATH` was always taken whatever its version, and a `bpd` handed a source map
 * it cannot read refuses the launch naming the `by` that wrote it (bpd 565f904), which says what to
 * do. Refusing to debug at all said less.
 *
 * The second one is not here. The interpreter is the one `by run` chooses, and only the wrapper
 * `by run` starts is told which — so the wrapper applies the order, with [Found.besideBy] as its
 * first answer and [Found.fallback] as its last. See [ByBpdWrapper].
 */
object ByBpdExecutable {

    /**
     * The `bpd`s the IDE can find itself: beside `by`, bundled with the plugin, and on `PATH`. Any
     * may be absent.
     */
    data class Found(val besideBy: Path?, val bundled: Path?, val onPath: Path?) {
        /**
         * What the wrapper falls back on when there is no `bpd` beside `by` or the interpreter: the
         * bundled one, else the one on `PATH`.
         */
        val fallback: Path? get() = bundled ?: onPath
    }

    /** The binary's name, without the platform's executable suffix. */
    const val BPD: String = "bpd"

    /** What the binary is called, per platform. */
    private fun name(): String = if (System.getProperty("os.name").lowercase().startsWith("windows")) {
        "$BPD.exe"
    } else {
        BPD
    }

    /**
     * The `bpd` beside the `by` [launch] starts, the one bundled with the plugin, and the one on
     * `PATH`. [bundled] is a parameter so a test can stand in for a plugin installation, which a
     * unit test has none of.
     */
    fun find(launch: ByLaunch?, bundled: () -> Path? = { BundledBinaries.find(BPD) }): Found =
        Found(besideBy(launch), bundled(), Executables.findOnPath(name()))

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
     *
     * A bundled `by` has the `bpd` bundled with it, in the same `bin/`, and that one comes through
     * [BundledBinaries.find]: the plugin installer drops unix modes, so it is not executable on
     * first use, and `find` is what puts the bit back for `by` and `buff`.
     */
    private fun besideBy(launch: ByLaunch?): Path? {
        if (launch == null || launch.kind == ByEnvironmentKind.UV) return null
        if (launch.kind == ByEnvironmentKind.BUNDLED) {
            // `<plugin>/bin/by`, so the plugin root is two up.
            return BundledBinaries.find(BPD, pluginRoot = launch.exe.parent?.parent)
        }
        val sibling = launch.exe.parent?.resolve(name()) ?: return null
        return if (Files.isRegularFile(sibling)) sibling else null
    }
}
