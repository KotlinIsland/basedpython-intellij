package dev.basedpython.pycharm.debug.bpd

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which `bpd` a session gets: the one installed with the toolchain the run uses.
 *
 * Only the answers that come from the launch are asserted; the last resort is whatever `PATH` holds
 * on the machine running the suite, which no test here controls.
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
    fun `a bpd beside the by the run starts is the one used`(@TempDir dir: Path) {
        val by = executable(dir.resolve("toolchain/by"))
        val bpd = executable(dir.resolve("toolchain/bpd"))
        executable(dir.resolve("env/bin/bpd"))
        val python = executable(dir.resolve("env/bin/python"))

        assertEquals(bpd, ByBpdExecutable.resolve(launch(by, ByEnvironmentKind.AUTO), python))
    }

    /**
     * A uv launch's executable is `uv`. What is beside it is whatever else was installed wherever
     * uv was — a `uv tool install`ed `bpd`, say — and says nothing about this project, whose `bpd`
     * is in the environment the program runs on.
     */
    @Test
    fun `a uv launch finds bpd in the environment rather than beside uv`(@TempDir dir: Path) {
        val uv = executable(dir.resolve("tools/uv"))
        val besideUv = executable(dir.resolve("tools/bpd"))
        val inEnvironment = executable(dir.resolve("project/.venv/bin/bpd"))
        val python = executable(dir.resolve("project/.venv/bin/python"))

        val found = ByBpdExecutable.resolve(launch(uv, ByEnvironmentKind.UV), python)
        assertNotEquals(besideUv, found, "the bpd beside uv is not this project's")
        assertEquals(inEnvironment, found)
    }
}
