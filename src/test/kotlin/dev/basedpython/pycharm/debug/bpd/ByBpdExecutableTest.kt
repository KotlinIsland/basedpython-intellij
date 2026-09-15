package dev.basedpython.pycharm.debug.bpd

import dev.basedpython.pycharm.env.ByEnvironmentKind
import dev.basedpython.pycharm.env.ByLaunch
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Which `bpd` the IDE can find before the run: the one installed with the toolchain the run uses.
 *
 * Only the answers that come from the launch are asserted; the one on `PATH` is whatever the
 * machine running the suite holds, which no test here controls. The one beside the interpreter is
 * the wrapper's to find — see [ByBpdWrapperExecutionTest].
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

    @Test
    fun `a by with no bpd beside it has none there`(@TempDir dir: Path) {
        val by = executable(dir.resolve("toolchain/by"))

        assertNull(ByBpdExecutable.find(launch(by, ByEnvironmentKind.AUTO)).besideBy)
        assertNull(ByBpdExecutable.find(null).besideBy)
    }
}
