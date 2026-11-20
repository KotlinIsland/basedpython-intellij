package dev.basedpython.pycharm.lang.dialect

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.junit5.fixture.tempPathFixture
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.withLocksForbidden
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Which project [BasedPythonFileTypeOverrider] decides a `.py` by while several are open, and that
 * finding it takes no lock.
 *
 * A file's type is asked for while Swing paints, where the platform forbids the read lock: the
 * Jupyter minimap asks whether an editor's file is a notebook from `MinimapPanel.paint`. With more
 * than one project open, `ProjectLocator.guessProjectForFile` asks each project's file index under
 * a read lock, and the overrider reported *"Attempt to take read lock"* from there.
 */
@TestApplication
@TestFixtures
class BasedPythonFileTypeOverriderProjectsTest {

    private val based by projectFixture(openAfterCreation = true)
    private val other by projectFixture(openAfterCreation = true)

    /**
     * Somewhere no project's base directory holds. Not `Files.createTempFile`: the JVM's temp
     * directory is the light test project's base, and a `.py` left there makes it a Python project
     * to every test after this one.
     */
    private val elsewhere by tempPathFixture()

    private fun fileIn(base: String?, relative: String, text: String = ""): VirtualFile {
        val path = Path.of(base!!).resolve(relative)
        path.parent.createDirectories()
        path.writeText(text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    @Test
    fun `a py is typed by the open project whose base directory holds it, without a lock`() {
        fileIn(based.basePath, "api.lock")
        for (project in listOf(based, other)) {
            BasedPythonSettings.getInstance(project).byEnabled = true
            BasedPythonSettings.getInstance(project).pyFileHandling = PyFileHandling.ALWAYS
        }
        val mine = fileIn(based.basePath, "pkg/mod.py")
        val theirs = fileIn(other.basePath, "mod.py")
        val nobodys = fileIn(elsewhere.toString(), "outside.py")

        val types = withLocksForbidden {
            val overrider = BasedPythonFileTypeOverrider()
            listOf(mine, theirs, nobodys).map(overrider::getOverriddenFileType)
        }

        assertSame(BasedPythonFileType.INSTANCE, types[0])
        // the other project carries no basedpython marker
        assertNull(types[1])
        assertNull(types[2])
        assertSame(based, BasedPythonFileTypeOverrider.projectOf(mine))
        assertSame(other, BasedPythonFileTypeOverrider.projectOf(theirs))
    }
}
