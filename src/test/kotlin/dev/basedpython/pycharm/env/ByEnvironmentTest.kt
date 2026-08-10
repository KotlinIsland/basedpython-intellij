package dev.basedpython.pycharm.env

import com.intellij.openapi.util.SystemInfo
import junit.framework.TestCase
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure-logic tests for [ByEnvironments]: path shapes, venv detection, and activation.
 *
 * No project fixture and no process launches — everything here is a function of paths on disk.
 * Resolution-order tests that need a [com.intellij.openapi.project.Project] live in
 * [dev.basedpython.pycharm.lsp.BasedPythonBinariesTest].
 */
class ByEnvironmentTest : TestCase() {

    private lateinit var tmp: Path

    override fun setUp() {
        super.setUp()
        tmp = Files.createTempDirectory("by-env-test")
    }

    override fun tearDown() {
        try {
            tmp.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** Creates `<root>/pyvenv.cfg` plus the bin dir, i.e. a venv as far as detection is concerned. */
    private fun makeVenv(root: Path): Path {
        Files.createDirectories(ByEnvironments.venvBinDir(root))
        Files.createFile(root.resolve("pyvenv.cfg"))
        return root
    }

    // --- venvBinDir / venvBinary -------------------------------------------

    fun `test venvBinDir follows the platform layout`() {
        val root = Path.of("/w/.venv")
        val expected = if (SystemInfo.isWindows) "Scripts" else "bin"
        assertEquals(root.resolve(expected), ByEnvironments.venvBinDir(root))
    }

    fun `test venvBinary adds the exe suffix only on Windows`() {
        val root = Path.of("/w/.venv")
        val by = ByEnvironments.venvBinary(root, "by")
        assertEquals(if (SystemInfo.isWindows) "by.exe" else "by", by.fileName.toString())
    }

    // --- venvRootOfInterpreter ---------------------------------------------

    fun `test venvRootOfInterpreter finds the root of a real venv`() {
        val root = makeVenv(tmp.resolve(".venv"))
        val python = ByEnvironments.venvBinary(root, "python")
        assertEquals(root, ByEnvironments.venvRootOfInterpreter(python))
    }

    fun `test venvRootOfInterpreter rejects an interpreter with no pyvenv cfg`() {
        // A system interpreter (/usr/bin/python3) must not be mistaken for a venv just because
        // its path happens to have the <root>/bin/<exe> shape.
        val bin = tmp.resolve("usr").resolve("bin")
        Files.createDirectories(bin)
        val python = bin.resolve("python3")
        Files.createFile(python)
        assertNull(ByEnvironments.venvRootOfInterpreter(python))
    }

    fun `test venvRootOfInterpreter tolerates a rootless path`() {
        assertNull(ByEnvironments.venvRootOfInterpreter(Path.of("python")))
    }

    // --- activationEnv ------------------------------------------------------

    fun `test activationEnv sets VIRTUAL_ENV to the venv root`() {
        val root = Path.of("/w/.venv")
        val env = ByEnvironments.activationEnv(root, parentPath = "/usr/bin")
        assertEquals(root.toString(), env["VIRTUAL_ENV"])
    }

    fun `test activationEnv puts the venv bin dir first on PATH`() {
        val root = Path.of("/w/.venv")
        val env = ByEnvironments.activationEnv(root, parentPath = "/usr/bin${File.pathSeparator}/bin")
        val bin = ByEnvironments.venvBinDir(root).toString()
        assertEquals("$bin${File.pathSeparator}/usr/bin${File.pathSeparator}/bin", env["PATH"])
    }

    fun `test activationEnv clears PYTHONHOME`() {
        // A leaked PYTHONHOME overrides the venv, so activation must neutralise it.
        val env = ByEnvironments.activationEnv(Path.of("/w/.venv"), parentPath = "/usr/bin")
        assertEquals("", env["PYTHONHOME"])
    }

    fun `test activationEnv copes with an empty parent PATH`() {
        val root = Path.of("/w/.venv")
        val env = ByEnvironments.activationEnv(root, parentPath = "")
        assertEquals(ByEnvironments.venvBinDir(root).toString(), env["PATH"])
    }

    fun `test activationEnv copes with a null parent PATH`() {
        val root = Path.of("/w/.venv")
        val env = ByEnvironments.activationEnv(root, parentPath = null)
        assertEquals(ByEnvironments.venvBinDir(root).toString(), env["PATH"])
    }

    // --- findVenvWithBinary (real files on a real filesystem) ---------------

    /** Creates an executable `<venvRoot>/bin/<name>`. */
    private fun installBinary(venvRoot: Path, name: String): Path {
        val exe = ByEnvironments.venvBinary(venvRoot, name)
        Files.createDirectories(exe.parent)
        Files.createFile(exe)
        exe.toFile().setExecutable(true)
        return exe
    }

    fun `test findVenvWithBinary finds a venv in the start directory`() {
        val venv = makeVenv(tmp.resolve(".venv"))
        installBinary(venv, "by")
        assertEquals(venv, ByEnvironments.findVenvWithBinary(listOf(tmp), "by"))
    }

    fun `test findVenvWithBinary walks up to a parent venv`() {
        val venv = makeVenv(tmp.resolve(".venv"))
        installBinary(venv, "by")
        val nested = tmp.resolve("a").resolve("b").resolve("c")
        Files.createDirectories(nested)
        assertEquals(venv, ByEnvironments.findVenvWithBinary(listOf(nested), "by"))
    }

    fun `test findVenvWithBinary stops after the walk-up limit`() {
        val venv = makeVenv(tmp.resolve(".venv"))
        installBinary(venv, "by")
        // 7 levels down is beyond the 5-hop budget, so the venv must not be found.
        var deep = tmp
        repeat(7) { deep = deep.resolve("d$it") }
        Files.createDirectories(deep)
        assertNull(ByEnvironments.findVenvWithBinary(listOf(deep), "by"))
    }

    fun `test findVenvWithBinary ignores a venv that lacks the binary`() {
        // An empty .venv must not shadow one further up that actually has basedpython installed.
        val outer = makeVenv(tmp.resolve(".venv"))
        installBinary(outer, "by")
        val moduleDir = tmp.resolve("module")
        makeVenv(moduleDir.resolve(".venv"))
        assertEquals(outer, ByEnvironments.findVenvWithBinary(listOf(moduleDir), "by"))
    }

    fun `test findVenvWithBinary prefers the first start dir that matches`() {
        // Mirrors the multi-root case: a per-module .venv wins over the workspace-level one.
        val workspaceVenv = makeVenv(tmp.resolve(".venv"))
        installBinary(workspaceVenv, "by")
        val moduleDir = tmp.resolve("module")
        Files.createDirectories(moduleDir)
        val moduleVenv = makeVenv(moduleDir.resolve(".venv"))
        installBinary(moduleVenv, "by")

        assertEquals(
            "the content root's own .venv should win",
            moduleVenv,
            ByEnvironments.findVenvWithBinary(listOf(moduleDir, tmp), "by"),
        )
    }

    fun `test findVenvWithBinary returns null when nothing matches`() {
        val empty = tmp.resolve("nothing")
        Files.createDirectories(empty)
        assertNull(ByEnvironments.findVenvWithBinary(listOf(empty), "by"))
    }

    // --- venvCandidatesAt / UV_PROJECT_ENVIRONMENT --------------------------

    /** Marks [dir] as a uv project root. */
    private fun makeUvProject(dir: Path): Path {
        Files.createDirectories(dir)
        Files.createFile(dir.resolve("pyproject.toml"))
        return dir
    }

    fun `test venvCandidatesAt offers only dot-venv when uv configures nothing`() {
        val dir = makeUvProject(tmp.resolve("proj"))
        assertEquals(listOf(dir.resolve(".venv")), ByEnvironments.venvCandidatesAt(dir, null))
        assertEquals(listOf(dir.resolve(".venv")), ByEnvironments.venvCandidatesAt(dir, ""))
    }

    fun `test venvCandidatesAt honours a relative uv environment at a project root`() {
        val dir = makeUvProject(tmp.resolve("proj"))
        assertEquals(
            listOf(dir.resolve(".venv"), dir.resolve("custom-env")),
            ByEnvironments.venvCandidatesAt(dir, "custom-env"),
        )
    }

    fun `test venvCandidatesAt handles a multi-segment uv environment`() {
        // uv accepts a path, not just a name — verified against uv 0.11.28.
        val dir = makeUvProject(tmp.resolve("proj"))
        assertEquals(
            listOf(dir.resolve(".venv"), dir.resolve("build").resolve("env")),
            ByEnvironments.venvCandidatesAt(dir, "build/env"),
        )
    }

    fun `test venvCandidatesAt takes an absolute uv environment as-is`() {
        val dir = makeUvProject(tmp.resolve("proj"))
        val abs = tmp.resolve("elsewhere").toAbsolutePath()
        assertEquals(listOf(dir.resolve(".venv"), abs), ByEnvironments.venvCandidatesAt(dir, abs.toString()))
    }

    fun `test venvCandidatesAt ignores the uv environment outside a uv project root`() {
        // The hijack guard. The variable is read once from the IDE's global environment, so probing
        // it anywhere but a uv project root would let one exported absolute value shadow the real
        // .venv of every unrelated project open in the IDE.
        val plain = tmp.resolve("not-a-uv-project")
        Files.createDirectories(plain)
        val abs = tmp.resolve("hijack").toAbsolutePath()
        assertEquals(
            "a non-uv directory must never adopt UV_PROJECT_ENVIRONMENT",
            listOf(plain.resolve(".venv")),
            ByEnvironments.venvCandidatesAt(plain, abs.toString()),
        )
    }

    fun `test a non-uv project with no venv never reaches an absolute uv environment`() {
        // The damaging shape of the hijack. A project with its own .venv is protected by ordering
        // alone, so that proves nothing; the exposure is a project with NO .venv, where an absolute
        // UV_PROJECT_ENVIRONMENT containing `by` would otherwise resolve and silently supply a
        // completely unrelated toolchain.
        val hijack = makeVenv(tmp.resolve("hijack"))
        installBinary(hijack, "by")
        val victim = tmp.resolve("victim")
        Files.createDirectories(victim)

        val resolved = ByEnvironments.venvCandidatesAt(victim, hijack.toString())
            .firstOrNull { Files.isExecutable(ByEnvironments.venvBinary(it, "by")) }
        assertNull("an unrelated project must not adopt another project's environment", resolved)
    }

    fun `test a uv project with no venv does reach its configured uv environment`() {
        // The flip side: where uv itself would look, so must we — otherwise the install banner's
        // `uv add --dev basedpython` lands somewhere detection cannot see and the banner never clears.
        val proj = makeUvProject(tmp.resolve("uvproj"))
        val env = makeVenv(proj.resolve("custom-env"))
        installBinary(env, "by")

        val resolved = ByEnvironments.venvCandidatesAt(proj, "custom-env")
            .firstOrNull { Files.isExecutable(ByEnvironments.venvBinary(it, "by")) }
        assertEquals(env, resolved)
    }

    fun `test dot-venv is preferred over the uv environment when both exist`() {
        val dir = makeUvProject(tmp.resolve("proj"))
        val candidates = ByEnvironments.venvCandidatesAt(dir, "custom-env")
        assertEquals("the conventional layout must be probed first", dir.resolve(".venv"), candidates.first())
    }

    // --- ByEnvironmentKind contract ----------------------------------------

    fun `test fromId round-trips every kind`() {
        for (kind in ByEnvironmentKind.entries) {
            assertEquals(kind, ByEnvironmentKind.fromId(kind.id))
        }
    }

    fun `test fromId degrades unknown and blank ids to AUTO`() {
        // Run configurations are VCS-shared. A config written by a newer plugin naming a source this
        // build has never heard of must still load, not fail.
        assertEquals(ByEnvironmentKind.AUTO, ByEnvironmentKind.fromId("conda"))
        assertEquals(ByEnvironmentKind.AUTO, ByEnvironmentKind.fromId(""))
        assertEquals(ByEnvironmentKind.AUTO, ByEnvironmentKind.fromId(null))
    }

    fun `test uv is offered as an explicit choice`() {
        // Opt-in, but it must still be pickable — that is the whole "manage its own env" story.
        assertTrue(ByEnvironmentKind.entries.contains(ByEnvironmentKind.UV))
    }

    fun `test every kind is offerable in the picker`() {
        // The Environment combo is built from `entries` and is not editable, and a non-editable
        // JComboBox silently ignores setSelectedItem for anything outside its model. A kind that
        // exists but is not offered would therefore load as AUTO and be written back on apply,
        // destroying the stored setting. So every kind must be a legitimate choice; anything that
        // is an *outcome* rather than a choice belongs on ByLaunch (see ByLaunch.fromOverride).
        for (kind in ByEnvironmentKind.entries) {
            assertTrue("$kind must be a real, selectable source", kind.display.isNotBlank())
        }
    }

    // --- searchStartDirs ----------------------------------------------------

    fun `test searchStartDirs prefers content root over project base`() {
        val root = Path.of("/work/moduleA")
        val base = Path.of("/work")
        assertEquals(listOf(root, base), ByEnvironments.searchStartDirs(root, base))
    }

    fun `test searchStartDirs dedupes when content root equals project base`() {
        val base = Path.of("/work")
        assertEquals(listOf(base), ByEnvironments.searchStartDirs(base, base))
    }

    fun `test searchStartDirs is empty when nothing is known`() {
        assertTrue(ByEnvironments.searchStartDirs(null, null).isEmpty())
    }
}
