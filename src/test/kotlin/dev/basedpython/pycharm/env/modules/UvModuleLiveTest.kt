package dev.basedpython.pycharm.env.modules

import dev.basedpython.pycharm.env.manager.EnvDependencyTarget
import dev.basedpython.pycharm.env.manager.EnvOp
import dev.basedpython.pycharm.env.manager.UvBackend
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * The module commands, run against a real uv, on a real workspace.
 *
 * The same join [dev.basedpython.pycharm.env.manager.UvLiveBackendTest] covers for the environment
 * commands, and it matters more here: this feature rests on three behaviours of `uv init` that are
 * documented nowhere in its `--help` and that nothing else in the test suite would notice changing.
 *
 * 1. Running it *inside* a project adds the new directory to `[tool.uv.workspace] members` — and
 *    creates that table when the project has none. The plugin therefore never writes a member entry
 *    itself, and would silently stop listing modules if uv stopped doing this.
 * 2. A directory an existing glob already covers is left alone rather than listed a second time.
 * 3. `uv add --package` writes both the requirement and the `[tool.uv.sources]` entry that makes a
 *    sibling resolve from the workspace instead of from the index.
 *
 * **Skipped unless `BASEDPYTHON_UV_UNDER_TEST` names a uv binary** — same rule, and same reason, as
 * the other live tests: a suite that behaves differently depending on what is installed on the
 * machine is worse than one that skips.
 */
@DisabledOnOs(OS.WINDOWS, disabledReason = "paths are compared as the posix separators uv writes")
class UvModuleLiveTest {

    private companion object {
        const val UV = "BASEDPYTHON_UV_UNDER_TEST"
        const val TIMEOUT_SECONDS = 120L
    }

    private fun uv(): Path? = System.getenv(UV)
        ?.let { Path.of(it) }
        ?.takeIf { Files.isExecutable(it) }

    /** The layout the plugin reads: uv's own member listing, through the backend. */
    private fun layout(uv: Path, dir: Path): ModuleLayout? {
        val (exit, output) = run(uv, dir, EnvOp.ListModules, mergeStderr = false)
        assertEquals(0, exit, "uv workspace list: $output")
        return UvBackend.moduleLayout(dir, output)
    }

    /** Runs one of the backend's own commands and returns (exit code, stdout + stderr). */
    private fun run(uv: Path, dir: Path, op: EnvOp, mergeStderr: Boolean = true): Pair<Int, String> {
        val command = requireNotNull(UvBackend.command(op)) { "uv cannot express $op" }
        val process = ProcessBuilder(listOf(uv.toString()) + command.args)
            .directory(dir.toFile())
            .apply {
                environment()["UV_CACHE_DIR"] = dir.resolve(".uv-cache").toString()
                environment()["UV_NO_CONFIG"] = "1"
                environment().remove("VIRTUAL_ENV")
            }
            .redirectErrorStream(mergeStderr)
            .apply { if (!mergeStderr) redirectError(ProcessBuilder.Redirect.DISCARD) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(
            process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS),
            "${command.describe("uv")} did not finish in ${TIMEOUT_SECONDS}s",
        )
        if (process.exitValue() != 0) println("${command.describe("uv")} -> ${process.exitValue()}: $output")
        return process.exitValue() to output
    }

    @Test
    fun `a real uv creates a module, lists it, and wires it into a sibling`(@TempDir dir: Path) {
        val uv = uv()
        assumeTrue(uv != null, "set $UV to a uv binary to run this")
        requireNotNull(uv)

        // A single-package project: no workspace table at all, which is the state the *New module*
        // button is most often pressed in.
        Files.writeString(
            dir.resolve(UvWorkspace.MANIFEST),
            """
            [project]
            name = "root"
            version = "0.1.0"
            requires-python = ">=3.9"
            dependencies = []
            """.trimIndent(),
        )

        val before = checkNotNull(layout(uv, dir))
        assertEquals("root", before.root?.name)
        assertFalse(before.isWorkspace, "a project with no members is not a workspace yet")

        // --- create ---
        val (initExit, initOutput) = run(
            uv,
            dir,
            EnvOp.InitModule(path = "packages/alpha", name = "alpha", kind = ModuleKind.LIBRARY),
        )
        assertEquals(0, initExit, "uv init: $initOutput")

        // uv listed it without being asked to, which is the whole reason the plugin does not.
        val listed = checkNotNull(layout(uv, dir))
        assertEquals(listOf("alpha"), listed.members.map { it.name })
        assertEquals("packages/alpha", listed.byName("alpha")?.relativePath)
        assertEquals(
            "packages/alpha",
            listed.byName("alpha")?.memberEntry,
            "an entry uv wrote by name is one the plugin can take back out",
        )
        assertTrue(listed.isWorkspace)

        // The flags that keep a module from being its own repository, or pinning its own Python.
        assertFalse(Files.exists(dir.resolve("packages/alpha/.git")), "no nested git repository")
        assertFalse(Files.exists(dir.resolve("packages/alpha/.python-version")), "no per-module pin")

        // --lib is a src layout with a build backend, which is what makes it importable.
        val module = checkNotNull(listed.byName("alpha"))
        assertTrue(module.packaged, "a library declares a build system")
        assertTrue(Files.isDirectory(dir.resolve("packages/alpha/src/alpha")), "a src layout")

        // --- a second module, under a glob this time ---
        Files.writeString(
            dir.resolve(UvWorkspace.MANIFEST),
            Files.readString(dir.resolve(UvWorkspace.MANIFEST))
                .replace("\"packages/alpha\",", "\"packages/*\","),
        )
        val (secondExit, secondOutput) = run(
            uv,
            dir,
            EnvOp.InitModule(path = "packages/beta", name = "beta", kind = ModuleKind.LIBRARY),
        )
        assertEquals(0, secondExit, "uv init beta: $secondOutput")

        val globbed = checkNotNull(layout(uv, dir))
        assertEquals(listOf("alpha", "beta"), globbed.members.map { it.name })
        assertNull(
            globbed.byName("beta")?.memberEntry,
            "a module a glob already covers is not listed again, and cannot be un-listed by name",
        )

        // --- wire beta into alpha, the way the dialog's checkbox does ---
        val (addExit, addOutput) = run(
            uv,
            dir,
            EnvOp.Add(listOf("beta"), EnvDependencyTarget.Main, module = "alpha"),
        )
        assertEquals(0, addExit, "uv add --package alpha beta: $addOutput")

        val wired = checkNotNull(layout(uv, dir))
        assertEquals(
            listOf("alpha"),
            wired.dependents("beta").map { it.name },
            "the dependency landed in alpha's manifest, not in the root's",
        )
        assertEquals(
            listOf(EnvDependencyTarget.Main),
            wired.byName("alpha")?.dependsOn("beta"),
        )
        assertTrue(
            Files.readString(dir.resolve("packages/alpha/${UvWorkspace.MANIFEST}")).contains("workspace = true"),
            "uv wrote the source entry that resolves beta locally",
        )
        assertNotNull(wired.byName("root"))

        // --- and back out again, which is what removing a module starts with ---
        val (removeExit, removeOutput) = run(
            uv,
            dir,
            EnvOp.Remove(listOf("beta"), EnvDependencyTarget.Main, module = "alpha"),
        )
        assertEquals(0, removeExit, "uv remove --package alpha beta: $removeOutput")
        assertTrue(checkNotNull(layout(uv, dir)).dependents("beta").isEmpty())
    }

    /**
     * The disagreement that moved member discovery to uv: a star under `packages` admits `build`
     * and dot-directories, and the plugin's own walk used to prune both.
     */
    @Test
    fun `the layout has exactly the members uv has`(@TempDir dir: Path) {
        val uv = uv()
        assumeTrue(uv != null, "set $UV to a uv binary to run this")
        requireNotNull(uv)

        Files.writeString(
            dir.resolve(UvWorkspace.MANIFEST),
            "[project]\nname = \"root\"\nversion = \"0.1.0\"\n\n[tool.uv.workspace]\nmembers = [\"packages/*\"]\n",
        )
        for ((path, name) in listOf("packages/alpha" to "alpha", "packages/build" to "bld", "packages/.hidden" to "hid")) {
            Files.createDirectories(dir.resolve(path))
            Files.writeString(
                dir.resolve(path).resolve(UvWorkspace.MANIFEST),
                "[project]\nname = \"$name\"\nversion = \"0.1.0\"\n",
            )
        }
        // No manifest: a directory the glob matches and uv does not count.
        Files.createDirectories(dir.resolve("packages/empty"))

        val layout = checkNotNull(layout(uv, dir))
        assertEquals(setOf("alpha", "bld", "hid"), layout.members.map { it.name }.toSet())
        assertEquals("root", layout.root?.name)
        assertFalse(Files.exists(dir.resolve("uv.lock")), "listing the modules wrote no lock file")
    }

    /** `uv version --frozen` edits the one line and creates neither a lock nor an environment. */
    @Test
    fun `setting a module's version touches only its manifest`(@TempDir dir: Path) {
        val uv = uv()
        assumeTrue(uv != null, "set $UV to a uv binary to run this")
        requireNotNull(uv)

        Files.writeString(
            dir.resolve(UvWorkspace.MANIFEST),
            "[project]\nname = \"root\"\nversion = \"0.1.0\"\n\n[tool.uv.workspace]\nmembers = [\"packages/alpha\"]\n",
        )
        Files.createDirectories(dir.resolve("packages/alpha"))
        val manifest = dir.resolve("packages/alpha").resolve(UvWorkspace.MANIFEST)
        Files.writeString(manifest, "[project]\nname = \"alpha\"\n# kept\nversion = \"0.1.0\"\n")

        val (exit, output) = run(uv, dir, EnvOp.SetVersion("0.2.0", module = "alpha"))
        assertEquals(0, exit, "uv version: $output")

        assertEquals("[project]\nname = \"alpha\"\n# kept\nversion = \"0.2.0\"\n", Files.readString(manifest))
        assertFalse(Files.exists(dir.resolve("uv.lock")))
        assertFalse(Files.exists(dir.resolve(".venv")))
    }
}
