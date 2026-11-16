package dev.basedpython.pycharm.debug.bpd

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which `bpd` the IDE can find before the run: the one installed with the toolchain the run uses,
 * and the one bundled with the plugin for when there is none.
 *
 * Only the answers that come from the launch and the plugin are asserted; the one on `PATH` is
 * whatever the machine running the suite holds, which no test here controls. The one beside the
 * interpreter is the wrapper's to find — see [ByBpdWrapperExecutionTest].
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "the bpd backend is refused on Windows by name")
class ByBpdExecutableTest {

    private fun executable(path: Path): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, "#!/bin/sh\n")
        path.toFile().setExecutable(true)
        return path
    }

    private fun launch(exe: Path, kind: ByEnvironmentKind) =
        ByLaunch(exe = exe, prependArgs = emptyList(), env = emptyMap(), venvRoot = null, kind = kind)

    @Test
    fun `a bpd beside the by the run starts is found`(@TempDir dir: Path) {
        val by = executable(dir.resolve("toolchain/by"))
        val bpd = executable(dir.resolve("toolchain/bpd"))

        assertEquals(bpd, ByBpdExecutable.find(launch(by, ByEnvironmentKind.AUTO)).besideBy)
    }

    /**
     * A uv launch's executable is `uv`. What is beside it is whatever else was installed wherever
     * uv was — a `uv tool install`ed `bpd`, say — and says nothing about this project, whose `bpd`
     * is in the environment the program runs on.
     */
    @Test
    fun `nothing beside uv is taken for the bpd beside by`(@TempDir dir: Path) {
        val uv = executable(dir.resolve("tools/uv"))
        executable(dir.resolve("tools/bpd"))

        assertNull(ByBpdExecutable.find(launch(uv, ByEnvironmentKind.UV)).besideBy)
    }

    /**
     * The plugin installer drops unix modes, so the `bpd` bundled beside the bundled `by` arrives
     * without its execute bit, and is only usable once it is put back.
     */
    @Test
    fun `the bpd bundled beside a bundled by is found and made executable`(@TempDir dir: Path) {
        val by = executable(dir.resolve("plugin/bin/by"))
        val bpd = dir.resolve("plugin/bin/bpd")
        Files.writeString(bpd, "#!/bin/sh\n")
        bpd.toFile().setExecutable(false)

        assertEquals(bpd, ByBpdExecutable.find(launch(by, ByEnvironmentKind.BUNDLED)).besideBy)
        assertTrue(Files.isExecutable(bpd), "the bundled bpd was found but left unable to run")
    }

    @Test
    fun `a bundled by with no bpd bundled beside it has none there`(@TempDir dir: Path) {
        val by = executable(dir.resolve("plugin/bin/by"))

        assertNull(ByBpdExecutable.find(launch(by, ByEnvironmentKind.BUNDLED)).besideBy)
    }

    @Test
    fun `a by with no bpd beside it has none there`(@TempDir dir: Path) {
        val by = executable(dir.resolve("toolchain/by"))

        assertNull(ByBpdExecutable.find(launch(by, ByEnvironmentKind.AUTO)).besideBy)
        assertNull(ByBpdExecutable.find(null).besideBy)
    }

    /**
     * A project whose `.venv` has `by` from `basedpython` but not `basedpython-debugger`: the plugin
     * ships a `bpd`, and it is what the wrapper falls back on rather than refusing the session.
     */
    @Test
    fun `the bundled bpd is the fallback when the by the run starts has none beside it`(@TempDir dir: Path) {
        val by = executable(dir.resolve("project/.venv/bin/by"))
        val bundled = executable(dir.resolve("plugin/bin/bpd"))

        val found = ByBpdExecutable.find(launch(by, ByEnvironmentKind.AUTO), bundled = { bundled })

        assertNull(found.besideBy)
        assertEquals(bundled, found.fallback)
    }

    /** One installed with the project's `by` is the toolchain it chose, and wins over the plugin's. */
    @Test
    fun `a bpd beside by is still preferred to the bundled one`(@TempDir dir: Path) {
        val by = executable(dir.resolve("project/.venv/bin/by"))
        val beside = executable(dir.resolve("project/.venv/bin/bpd"))
        val bundled = executable(dir.resolve("plugin/bin/bpd"))

        val found = ByBpdExecutable.find(launch(by, ByEnvironmentKind.AUTO), bundled = { bundled })

        assertEquals(beside, found.besideBy)
    }

    /** Bundled before `PATH`, the order `by` itself is resolved in. */
    @Test
    fun `the bundled bpd is preferred to the one on PATH`(@TempDir dir: Path) {
        val bundled = dir.resolve("plugin/bin/bpd")
        val onPath = dir.resolve("usr/bin/bpd")

        assertEquals(bundled, ByBpdExecutable.Found(besideBy = null, bundled = bundled, onPath = onPath).fallback)
        assertEquals(onPath, ByBpdExecutable.Found(besideBy = null, bundled = null, onPath = onPath).fallback)
        assertNull(ByBpdExecutable.Found(besideBy = null, bundled = null, onPath = null).fallback)
    }
}
