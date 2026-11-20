package dev.basedpython.pycharm.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
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
internal class BasedPythonProjectScaffoldLiveTest {

  private companion object {
    const val BUFF = "BASEDPYTHON_BUFF_UNDER_TEST"
    const val UV = "BASEDPYTHON_UV_UNDER_TEST"
    const val TIMEOUT_SECONDS = 120L
    val POLLS_MODELS = """
from django.db import models


class Question(models.Model):
    text = models.CharField(max_length=200)


class Choice(models.Model):
    question = models.ForeignKey(Question, on_delete=models.CASCADE)
    votes    = models.IntegerField(default=0)


def questions_about(word: str) -> int:
    return Question.objects.filter(text__contains=word).count()
""".trimStart()
  }

  @TempDir
  lateinit var dir: Path

  private fun binary(variable: String): Path? =
    System.getenv(variable)?.let { Path.of(it) }?.takeIf { Files.isExecutable(it) }

  private fun scaffold(template: BasedPythonProjectScaffold.Template = BasedPythonProjectScaffold.Template.PLAIN): Path {
    val project = dir.resolve("scaffolded")
    val files = BasedPythonProjectScaffold.files("scaffolded", template) +
      // a django project with an app in it, as New | Django App writes one
      if (template == BasedPythonProjectScaffold.Template.DJANGO) ByDjangoScaffold.appFiles("polls") else emptyMap()
    for ((relative, content) in files) {
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

  @ParameterizedTest
  @EnumSource(BasedPythonProjectScaffold.Template::class)
  fun `buff accepts the configuration and the source as scaffolded`(template: BasedPythonProjectScaffold.Template) {
    val buff = binary(BUFF)
    assumeTrue(buff != null, "$BUFF is not set")
    val project = scaffold(template)

    val (checkExit, checkOutput) = run(project, buff.toString(), "check", "--no-cache", ".")
    assertEquals(0, checkExit, checkOutput)
    val (formatExit, formatOutput) = run(project, buff.toString(), "format", "--check", "--no-cache", ".")
    assertEquals(0, formatExit, formatOutput)
  }

  @ParameterizedTest
  @EnumSource(BasedPythonProjectScaffold.Template::class)
  fun `uv reads the pyproject and syncs the project`(template: BasedPythonProjectScaffold.Template) {
    val uv = binary(UV)
    assumeTrue(uv != null, "$UV is not set")
    val project = scaffold(template)

    val (exit, output) = run(project, uv.toString(), "sync")
    assertEquals(0, exit, output)
    if (template == BasedPythonProjectScaffold.Template.DJANGO) {
      // django itself accepts the settings and the app: its system checks pass and the app's
      // configuration loads once it is installed
      val settings = project.resolve("scaffolded/settings.py")
      Files.writeString(settings, Files.readString(settings).replace("\"django.contrib.staticfiles\",", "\"django.contrib.staticfiles\",\n    \"polls\","))
      val (checkExit, checkOutput) = run(project, uv.toString(), "run", "python", "manage.py", "check")
      assertEquals(0, checkExit, checkOutput)
    }
  }

  /** The `by` the dev dependencies install reports nothing, warnings included, which it exits 1 on. */
  private fun byChecksClean(template: BasedPythonProjectScaffold.Template) {
    val uv = binary(UV)
    assumeTrue(uv != null, "$UV is not set")
    val project = scaffold(template)
    val (syncExit, syncOutput) = run(project, uv.toString(), "sync")
    assertEquals(0, syncExit, syncOutput)
    if (template == BasedPythonProjectScaffold.Template.DJANGO) {
      // a model queried through its default manager, which by reports as missing without django-stubs
      Files.writeString(project.resolve("polls/models.py"), POLLS_MODELS)
    }
    val (exit, output) = run(project, project.resolve(".venv/bin/by").toString(), "check", "--output-format", "concise")
    assertEquals(0, exit, output)
  }

  @Test
  fun `by reports nothing on the plain project`() = byChecksClean(BasedPythonProjectScaffold.Template.PLAIN)

  /**
   * With django-stubs the model's `objects` and `IntegerField(default=0)` are clean, but by 0.0.3
   * (c2f7fc12b, and fd6eb3971) reports every `from django…` in the project's own package as
   * `misplaced-dependency` — "`django` comes from `django_stubs`, which is declared in dependency
   * group `dev`" — though django itself is a runtime dependency and a stubs package belongs in dev.
   */
  @Test
  @Disabled("by reports a stubs package in the dev group as misplaced-dependency; see the doc")
  fun `by reports nothing on the django project`() = byChecksClean(BasedPythonProjectScaffold.Template.DJANGO)
}
