package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Which files this plugin runs and debugs.
 *
 * The interesting case is `.py`, and it is interesting because the answer is not about running or
 * debugging at all: a `.py` file is ours exactly when it is ours as a *file type*, which the project
 * markers and the *Settings | basedpython* ownership choice decide between them. Claiming a `.py`
 * PyCharm owns would mean two run configurations offered on the file and a "choose a breakpoint
 * type" popup on every gutter click in it.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BasedPythonSourcesTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private val createdMarkers = mutableListOf<Path>()

    private fun settings() = BasedPythonSettings.getInstance(project)

    /** A project the plugin recognises, with `.py` pinned to us so the IDE under test cannot sway it. */
    private fun makeBasedPythonProject(handling: PyFileHandling = PyFileHandling.ALWAYS) {
        settings().byEnabled = true
        settings().pyFileHandling = handling
        val base = Paths.get(project.basePath!!)
        if (!Files.exists(base)) {
            Files.createDirectories(base)
            createdMarkers.add(base)
        }
        val marker = base.resolve("api.lock")
        if (!Files.exists(marker)) {
            Files.createFile(marker)
            createdMarkers.add(marker)
        }
    }

    private fun file(relPath: String): VirtualFile =
        fixture.addFileToProject(relPath, "x = 1\n").virtualFile

    @AfterEach
    fun removeMarkers() {
        for (path in createdMarkers.reversed()) {
            try {
                Files.deleteIfExists(path)
            } catch (_: Exception) {
            }
        }
        createdMarkers.clear()
    }

    @Test
    fun `a by file always accepts a breakpoint`() {
        makeBasedPythonProject()
        assertTrue(BasedPythonSources.isOwnedSource(file("main.by")))
    }

    /** `by run` never transpiles this file, so the breakpoint lands on the file itself. */
    @Test
    fun `a py file we own accepts a breakpoint`() {
        makeBasedPythonProject()
        assertTrue(BasedPythonSources.isOwnedSource(file("helper.py")))
    }

    /**
     * NEVER is the setting a PyCharm user leaves `.py` to the Python plugin with, and a breakpoint
     * of ours there would be the second type on the line.
     */
    @Test
    fun `a py file we do not own does not`() {
        makeBasedPythonProject(handling = PyFileHandling.NEVER)
        assertFalse(BasedPythonSources.isOwnedSource(file("untouched.py")))
    }

    /** Stubs declare, they do not execute — neither dialect's. */
    @Test
    fun `stubs never do`() {
        makeBasedPythonProject()
        assertFalse(BasedPythonSources.isOwnedSource(file("shape.byi")))
        assertFalse(BasedPythonSources.isOwnedSource(file("shape.pyi")))
    }

    @Test
    fun `an unrelated file does not, and neither does no file at all`() {
        makeBasedPythonProject()
        assertFalse(BasedPythonSources.isOwnedSource(file("notes.md")))
        assertFalse(BasedPythonSources.isOwnedSource(null))
    }
}
