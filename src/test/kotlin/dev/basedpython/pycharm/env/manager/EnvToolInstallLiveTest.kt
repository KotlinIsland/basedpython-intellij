package dev.basedpython.pycharm.env.manager

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The one-click uv install, run for real: fetch the release archive, unpack the one entry, and check
 * the result is a uv that answers `--version`.
 *
 * This is the piece with the most ways to be quietly wrong and the fewest ways to notice — a target
 * triple that 404s, an archive whose executable is one directory deeper than expected, an entry
 * filter that matches nothing, an execute bit that a zip did not carry. All of them present
 * identically to the user: the button appears to work and uv is still missing. [UvDownloadTest]
 * checks the URL this builds; only running it checks that the URL serves an archive this can open.
 *
 * **Skipped unless `BASEDPYTHON_ALLOW_NETWORK_TESTS=1`.** It downloads tens of megabytes from
 * GitHub, so it is not something an ordinary `./gradlew test` should do.
 */
class EnvToolInstallLiveTest {

    private companion object {
        const val NETWORK = "BASEDPYTHON_ALLOW_NETWORK_TESTS"
        const val TIMEOUT_SECONDS = 30L
    }

    @Test
    fun `the install button fetches a uv that runs`(@TempDir dir: Path) {
        assumeTrue(System.getenv(NETWORK) == "1", "set $NETWORK=1 to allow the download")
        assumeTrue(UvDownload.current() != null, "uv publishes nothing for this platform")

        val target = dir.resolve("bin").resolve(EnvTools.executableName("uv"))
        val outcome = EnvToolInstall.install(UvBackend, indicator = null, target = target)

        assertTrue(
            outcome is EnvToolInstall.Outcome.Installed,
            "install failed: $outcome",
        )
        assertEquals(target, (outcome as EnvToolInstall.Outcome.Installed).path)
        assertTrue(Files.isExecutable(target), "the installed binary carries its execute bit")

        // Nothing but the executable was unpacked into the install directory — the entry filter is
        // what keeps an archive from writing anything else next to the plugin's own binaries.
        val installed = Files.list(target.parent).use { it.map { p -> p.fileName.toString() }.toList() }
        assertEquals(listOf(target.fileName.toString()), installed.sorted())

        // The real check: it is uv, and it runs on this machine.
        val process = ProcessBuilder(target.toString(), "--version").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS), "uv --version did not finish")
        assertEquals(0, process.exitValue(), output)
        assertTrue(output.startsWith("uv "), "unexpected output: $output")
    }
}
