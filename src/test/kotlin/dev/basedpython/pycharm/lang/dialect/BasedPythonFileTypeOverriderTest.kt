package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Tests for [BasedPythonFileTypeOverrider].
 *
 * Two layers are exercised:
 *   1. The pure [BasedPythonFileTypeOverrider.decide] / [isOverridableExtension] logic,
 *      which needs no IDE state.
 *   2. The full [BasedPythonFileTypeOverrider.getOverriddenFileType] path against real
 *      [VirtualFile]s created through `myFixture`, with the project toggled into / out of
 *      "basedpython project" mode via on-disk markers at the base path.
 */
class BasedPythonFileTypeOverriderTest : BasePlatformTestCase() {

    private val overrider = BasedPythonFileTypeOverrider()
    private val createdMarkers = mutableListOf<Path>()

    private fun base(): Path = Paths.get(project.basePath!!)
    private fun settings() = BasedPythonSettings.getInstance(project)

    private fun makeBasedPythonProject() {
        settings().byEnabled = true
        val base = base()
        if (!Files.exists(base)) {
            Files.createDirectories(base)
            createdMarkers.add(base)
        }
        val marker = base.resolve("pyproject.toml")
        if (!Files.exists(marker)) {
            Files.createFile(marker)
            createdMarkers.add(marker)
        }
    }

    private fun makeVanillaProject() {
        settings().byEnabled = true
        // Ensure no markers linger at base from a previous run.
        for (name in listOf("pyproject.toml", "api.lock")) {
            try {
                Files.deleteIfExists(base().resolve(name))
            } catch (_: Exception) {
            }
        }
    }

    /** Creates a real project file via the fixture and returns its [VirtualFile]. */
    private fun fixtureFile(relPath: String): VirtualFile =
        myFixture.addFileToProject(relPath, "x = 1\n").virtualFile

    override fun tearDown() {
        try {
            for (p in createdMarkers.reversed()) {
                try {
                    Files.deleteIfExists(p)
                } catch (_: Exception) {
                }
            }
            createdMarkers.clear()
        } finally {
            super.tearDown()
        }
    }

    // =========================================================================
    // pure decision logic
    // =========================================================================

    fun `test decide returns basedpython for py in basedpython project`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            BasedPythonFileTypeOverrider.decide("py", isBasedPythonProject = true),
        )
    }

    fun `test decide returns null for py in non-basedpython project`() {
        assertNull(BasedPythonFileTypeOverrider.decide("py", isBasedPythonProject = false))
    }

    fun `test decide returns null for pyi even in basedpython project`() {
        assertNull(BasedPythonFileTypeOverrider.decide("pyi", isBasedPythonProject = true))
    }

    fun `test decide returns null for by which is already handled`() {
        assertNull(BasedPythonFileTypeOverrider.decide("by", isBasedPythonProject = true))
    }

    fun `test decide returns null for unrelated extension`() {
        assertNull(BasedPythonFileTypeOverrider.decide("md", isBasedPythonProject = true))
    }

    fun `test decide returns null for null extension`() {
        assertNull(BasedPythonFileTypeOverrider.decide(null, isBasedPythonProject = true))
    }

    fun `test decide is case insensitive on extension`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            BasedPythonFileTypeOverrider.decide("PY", isBasedPythonProject = true),
        )
    }

    fun `test isOverridableExtension only accepts py`() {
        assertTrue(BasedPythonFileTypeOverrider.isOverridableExtension("py"))
        assertTrue(BasedPythonFileTypeOverrider.isOverridableExtension("PY"))
        assertFalse(BasedPythonFileTypeOverrider.isOverridableExtension("pyi"))
        assertFalse(BasedPythonFileTypeOverrider.isOverridableExtension("by"))
        assertFalse(BasedPythonFileTypeOverrider.isOverridableExtension(null))
    }

    // =========================================================================
    // full getOverriddenFileType path with real VirtualFiles
    // =========================================================================

    fun `test py in basedpython project is overridden to basedpython`() {
        makeBasedPythonProject()
        val file = fixtureFile("script.py")
        assertSame(BasedPythonFileType.INSTANCE, overrider.getOverriddenFileType(file))
    }

    fun `test py in vanilla project is not overridden`() {
        makeVanillaProject()
        val file = fixtureFile("script.py")
        assertNull(overrider.getOverriddenFileType(file))
    }

    fun `test pyi is never overridden even in basedpython project`() {
        makeBasedPythonProject()
        val file = fixtureFile("stub.pyi")
        assertNull(overrider.getOverriddenFileType(file))
    }

    fun `test by file is not overridden by this overrider`() {
        makeBasedPythonProject()
        val file = fixtureFile("module.by")
        assertNull(overrider.getOverriddenFileType(file))
    }

    fun `test non-source extension is not overridden`() {
        makeBasedPythonProject()
        val file = fixtureFile("notes.md")
        assertNull(overrider.getOverriddenFileType(file))
    }

    fun `test py with by disabled is not overridden`() {
        makeBasedPythonProject()
        settings().byEnabled = false
        val file = fixtureFile("script.py")
        assertNull(overrider.getOverriddenFileType(file))
    }
}
