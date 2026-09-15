package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.fileTypes.FileTypeListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.FileTypeEvent
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.PlatformTestUtil
import org.junit.jupiter.api.Assertions.assertEquals

/**
 * Tests for [BasedPythonFileTypeOverrider].
 *
 * Two layers are exercised:
 *   1. The pure [BasedPythonFileTypeOverrider.decide] / [isOverridableExtension] logic,
 *      which needs no IDE state.
 *   2. The full [BasedPythonFileTypeOverrider.getOverriddenFileType] path against real
 *      [VirtualFile]s created through the fixture, with the project toggled into / out of
 *      "basedpython project" mode via on-disk markers at the base path.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonFileTypeOverriderTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private val overrider = BasedPythonFileTypeOverrider()
    private val createdMarkers = mutableListOf<String>()

    private fun settings() = BasedPythonSettings.getInstance(project)

    /**
     * The project base directory, through the VFS — which is how markers are written here too: the
     * detector's verdict is dropped by VFS events, as it is when a user or `git` changes the files.
     */
    private fun baseDir(): VirtualFile =
        WriteAction.computeAndWait<VirtualFile, RuntimeException> { VfsUtil.createDirectories(project.basePath!!) }

    private fun createMarker(name: String, content: String = "") {
        WriteAction.runAndWait<RuntimeException> {
            val file = baseDir().findChild(name) ?: baseDir().createChildData(this, name).also { createdMarkers += name }
            VfsUtil.saveText(file, content)
        }
    }

    private fun deleteMarker(name: String) {
        WriteAction.runAndWait<RuntimeException> { baseDir().findChild(name)?.delete(this) }
    }

    private fun makeBasedPythonProject() {
        settings().byEnabled = true
        // Pin the ownership choice so the outcome does not depend on whether the IDE running the
        // tests happens to provide the Python language.
        settings().pyFileHandling = PyFileHandling.ALWAYS
        // A bare pyproject.toml is no longer enough — it has to mention basedpython.
        createMarker("api.lock")
    }

    private fun makeVanillaProject() {
        settings().byEnabled = true
        // Ensure no markers linger at base from a previous run.
        for (name in listOf("pyproject.toml", "api.lock", "basedpython.toml")) deleteMarker(name)
    }

    /** Creates a real project file via the fixture and returns its [VirtualFile]. */
    private fun fixtureFile(relPath: String): VirtualFile =
        fixture.addFileToProject(relPath, "x = 1\n").virtualFile

    @AfterEach
    fun removeMarkers() {
        for (name in createdMarkers) deleteMarker(name)
        createdMarkers.clear()
        settings().loadState(BasedPythonSettings.State())
    }

    // =========================================================================
    // pure decision logic
    // =========================================================================

    /** [BasedPythonFileTypeOverrider.decide] with the settings that make it claim the file. */
    private fun decide(
        extension: String?,
        isBasedPythonProject: Boolean = true,
        handling: PyFileHandling = PyFileHandling.ALWAYS,
        pythonLanguageAvailable: Boolean = false,
    ) = BasedPythonFileTypeOverrider.decide(
        extension, isBasedPythonProject, handling, pythonLanguageAvailable,
    )

    @Test
    fun `decide returns basedpython for py in basedpython project`() {
        assertSame(BasedPythonFileType.INSTANCE, decide("py"))
    }

    @Test
    fun `decide returns null for py in non-basedpython project`() {
        assertNull(decide("py", isBasedPythonProject = false))
    }

    @Test
    fun `decide returns null for pyi even in basedpython project`() {
        assertNull(decide("pyi"))
    }

    @Test
    fun `decide returns null for by which is already handled`() {
        assertNull(decide("by"))
    }

    @Test
    fun `decide returns null for unrelated extension`() {
        assertNull(decide("md"))
    }

    @Test
    fun `decide returns null for null extension`() {
        assertNull(decide(null))
    }

    @Test
    fun `decide is case insensitive on extension`() {
        assertSame(BasedPythonFileType.INSTANCE, decide("PY"))
    }

    // ---- who owns .py (§ "work alongside PyCharm") ----

    @Test
    fun `NEVER leaves py alone even in a basedpython project`() {
        assertNull(decide("py", handling = PyFileHandling.NEVER, pythonLanguageAvailable = false))
    }

    @Test
    fun `ALWAYS claims py even when a Python plugin is present`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            decide("py", handling = PyFileHandling.ALWAYS, pythonLanguageAvailable = true),
        )
    }

    @Test
    fun `AUTO claims py only when nothing else provides Python`() {
        assertSame(
            BasedPythonFileType.INSTANCE,
            decide("py", handling = PyFileHandling.AUTO, pythonLanguageAvailable = false),
        )
        assertNull(decide("py", handling = PyFileHandling.AUTO, pythonLanguageAvailable = true))
    }

    @Test
    fun `isOverridableExtension only accepts py`() {
        assertTrue(BasedPythonFileTypeOverrider.isOverridableExtension("py"))
        assertTrue(BasedPythonFileTypeOverrider.isOverridableExtension("PY"))
        assertFalse(BasedPythonFileTypeOverrider.isOverridableExtension("pyi"))
        assertFalse(BasedPythonFileTypeOverrider.isOverridableExtension("by"))
        assertFalse(BasedPythonFileTypeOverrider.isOverridableExtension(null))
    }

    // =========================================================================
    // full getOverriddenFileType path with real VirtualFiles
    // =========================================================================

    @Test
    fun `py in basedpython project is overridden to basedpython`() {
        makeBasedPythonProject()
        val file = fixtureFile("script.py")
        assertSame(BasedPythonFileType.INSTANCE, overrider.getOverriddenFileType(file))
    }

    @Test
    fun `py in vanilla project is not overridden`() {
        makeVanillaProject()
        val file = fixtureFile("script.py")
        assertNull(overrider.getOverriddenFileType(file))
    }

    @Test
    fun `pyi is never overridden even in basedpython project`() {
        makeBasedPythonProject()
        val file = fixtureFile("stub.pyi")
        assertNull(overrider.getOverriddenFileType(file))
    }

    @Test
    fun `by file is not overridden by this overrider`() {
        makeBasedPythonProject()
        val file = fixtureFile("module.by")
        assertNull(overrider.getOverriddenFileType(file))
    }

    @Test
    fun `non-source extension is not overridden`() {
        makeBasedPythonProject()
        val file = fixtureFile("notes.md")
        assertNull(overrider.getOverriddenFileType(file))
    }

    @Test
    fun `py with by disabled is not overridden`() {
        makeBasedPythonProject()
        settings().byEnabled = false
        val file = fixtureFile("script.py")
        assertNull(overrider.getOverriddenFileType(file))
    }

    // =========================================================================
    // when the verdict is read again
    // =========================================================================

    private fun cache() = project.getService(BasedPythonProjectKindCache::class.java)

    @Test
    fun `a file created outside the base directory does not cost a rescan`() {
        makeVanillaProject()
        // The directory itself is an entry of the base directory, so it is made before the baseline.
        val sub = WriteAction.computeAndWait<VirtualFile, RuntimeException> {
            VfsUtil.createDirectories(project.basePath!! + "/sub")
        }
        BasedPythonProjectDetector.kind(project)
        val scans = cache().scans

        fixtureFile("pkg/deep/elsewhere.py")
        WriteAction.runAndWait<RuntimeException> { sub.createChildData(this, "unrelated.txt") }
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
        BasedPythonProjectDetector.kind(project)

        assertEquals(scans, cache().scans)
        WriteAction.runAndWait<RuntimeException> { baseDir().findChild("sub")?.delete(this) }
    }

    @Test
    fun `editing pyproject toml changes the verdict`() {
        makeVanillaProject()
        createMarker("pyproject.toml", "[project]\nname = \"plain\"\n")
        assertEquals(ProjectKind.PYTHON, BasedPythonProjectDetector.kind(project))

        createMarker("pyproject.toml", "[project]\nname = \"based\"\ndependencies = [\"basedpython\"]\n")

        assertEquals(ProjectKind.BASEDPYTHON, BasedPythonProjectDetector.kind(project))
    }

    /** Files the platform typed before the change have to be typed again, or open `.py` files keep the old type. */
    @Test
    fun `a verdict that flips tells the platform file types changed`() {
        makeVanillaProject()
        assertEquals(false, BasedPythonProjectDetector.kind(project) == ProjectKind.BASEDPYTHON)
        var changes = 0
        val disposable = Disposer.newDisposable()
        try {
            project.messageBus.connect(disposable).subscribe(FileTypeManager.TOPIC, object : FileTypeListener {
                override fun fileTypesChanged(event: FileTypeEvent) {
                    changes++
                }
            })

            createMarker("api.lock")

            val deadline = System.currentTimeMillis() + 10_000
            while (changes == 0 && System.currentTimeMillis() < deadline) {
                PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
                Thread.sleep(10)
            }
            assertTrue(changes > 0, "no file type change after the project became basedpython")
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
