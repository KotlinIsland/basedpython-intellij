package dev.basedpython.pycharm.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * A freshly scaffolded project, handed to the tools its README tells the user to run next.
 *
 * Checked against the real binaries because the failures this guards against were schema errors
 * only those tools report: `buff` refused `quote-style` under `[tool.ruff]` as an unknown field, and
 * uv refused `[tool.uv.dev-dependencies]` as a table where it wants a list. Neither is something a
 * string comparison would ever have caught.
 *
 * **Each check is skipped unless its binary is named** — `BASEDPYTHON_BUFF_UNDER_TEST` for `buff`,
 * `BASEDPYTHON_UV_UNDER_TEST` for uv — the same rule the other live tests follow.
 */
class BasedPythonProjectScaffoldLiveTest {

  private companion object {
    const val BUFF = "BASEDPYTHON_BUFF_UNDER_TEST"
    const val UV = "BASEDPYTHON_UV_UNDER_TEST"
    const val TIMEOUT_SECONDS = 120L
  }

  @TempDir
  lateinit var dir: Path

  private fun binary(variable: String): Path? =
    System.getenv(variable)?.let { Path.of(it) }?.takeIf { Files.isExecutable(it) }

  private fun scaffold(): Path {
    val project = dir.resolve("scaffolded")
    for ((relative, content) in BasedPythonProjectScaffold.files("scaffolded")) {
      val file = project.resolve(relative)
      Files.createDirectories(file.parent)
      Files.writeString(file, content)
    }
    return project
  }

  private fun run(project: Path, vararg command: String): Pair<Int, String> {
    val process = ProcessBuilder(*command)
      .directory(project.toFile())
      .apply {
        environment()["UV_CACHE_DIR"] = dir.resolve(".uv-cache").toString()
        environment()["UV_NO_CONFIG"] = "1"
        environment().remove("VIRTUAL_ENV")
      }
      .redirectErrorStream(true)
      .start()
    check(process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "${command.toList()} timed out" }
    return process.exitValue() to process.inputStream.bufferedReader().readText()
  }

  @Test
  fun `buff accepts the configuration and the source as scaffolded`() {
    val buff = binary(BUFF)
    assumeTrue(buff != null, "$BUFF is not set")
    val project = scaffold()

    val (checkExit, checkOutput) = run(project, buff.toString(), "check", "--no-cache", ".")
    assertEquals(0, checkExit, checkOutput)
    val (formatExit, formatOutput) = run(project, buff.toString(), "format", "--check", "--no-cache", ".")
    assertEquals(0, formatExit, formatOutput)
  }

  @Test
  fun `uv reads the pyproject and syncs the project`() {
    val uv = binary(UV)
    assumeTrue(uv != null, "$UV is not set")
    val project = scaffold()

    val (exit, output) = run(project, uv.toString(), "sync")
    assertEquals(0, exit, output)
  }
}
