package dev.basedpython.pycharm.lang.dialect

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Decision-logic tests for [BasedPythonProjectDetector].
 *
 * The detector reads marker files directly off the project base path via `java.nio`,
 * so each test materialises (or removes) real files at [BasePlatformTestCase.getProject]'s
 * base path and then asserts the boolean outcome. Any files created are tracked and
 * deleted in [tearDown] so tests stay isolated.
 */
class BasedPythonProjectDetectorTest : BasePlatformTestCase() {

    private val created = mutableListOf<Path>()

    private fun base(): Path = Paths.get(project.basePath!!)

    private fun settings() = BasedPythonSettings.getInstance(project)

    private fun ensureBaseDir(): Path {
        val base = base()
        if (!Files.exists(base)) {
            Files.createDirectories(base)
            created.add(base)
        }
        return base
    }

    /** Create a regular file named [name] at the project base; track for cleanup. */
    private fun createMarker(name: String) {
        val base = ensureBaseDir()
        val p = base.resolve(name)
        if (!Files.exists(p)) {
            Files.createFile(p)
            created.add(p)
        }
    }

    override fun setUp() {
        super.setUp()
        // Documented default is byEnabled = true; make tests explicit anyway.
        settings().byEnabled = true
    }

    override fun tearDown() {
        try {
            // Delete in reverse so files go before the dir we may have made.
            for (p in created.reversed()) {
                try {
                    Files.deleteIfExists(p)
                } catch (_: Exception) {
                    // best-effort cleanup
                }
            }
            created.clear()
        } finally {
            super.tearDown()
        }
    }

    // -------------------------------------------------------------------------
    // positive cases: byEnabled + a marker
    // -------------------------------------------------------------------------

    fun `test pyproject marker with by enabled is a basedpython project`() {
        createMarker("pyproject.toml")
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test api lock marker with by enabled is a basedpython project`() {
        createMarker("api.lock")
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test top-level by file with by enabled is a basedpython project`() {
        createMarker("main.by")
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test any one of several markers is enough`() {
        createMarker("pyproject.toml")
        createMarker("api.lock")
        createMarker("module.by")
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    // -------------------------------------------------------------------------
    // negative: no marker
    // -------------------------------------------------------------------------

    fun `test vanilla project with no markers is not basedpython`() {
        // No markers created.
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test unrelated file at base is not a marker`() {
        createMarker("README.md")
        createMarker("notes.txt")
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test a py file at base is not a marker on its own`() {
        // Only `.by` (not `.py`) counts as a source marker.
        createMarker("script.py")
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    // -------------------------------------------------------------------------
    // negative: byEnabled gate
    // -------------------------------------------------------------------------

    fun `test marker present but by disabled is not basedpython`() {
        createMarker("pyproject.toml")
        settings().byEnabled = false
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test by file present but by disabled is not basedpython`() {
        createMarker("main.by")
        settings().byEnabled = false
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    fun `test disabling then re-enabling by flips the result`() {
        createMarker("api.lock")

        settings().byEnabled = false
        assertFalse(BasedPythonProjectDetector.isBasedPythonProject(project))

        settings().byEnabled = true
        assertTrue(BasedPythonProjectDetector.isBasedPythonProject(project))
    }

    // -------------------------------------------------------------------------
    // stability / purity
    // -------------------------------------------------------------------------

    fun `test repeated calls are stable and side-effect free`() {
        createMarker("pyproject.toml")
        val first = BasedPythonProjectDetector.isBasedPythonProject(project)
        val second = BasedPythonProjectDetector.isBasedPythonProject(project)
        assertEquals(first, second)
        assertTrue(first)
        // Calling the detector must not have created any files.
        assertFalse(
            "detector must not create api.lock",
            Files.exists(base().resolve("api.lock")),
        )
    }
}
