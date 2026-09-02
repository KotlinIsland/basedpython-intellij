package dev.basedpython.pycharm.project

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.util.IconUtil
import dev.basedpython.pycharm.BasedPythonIcons
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Which files get the icon, and that the registration actually delivers it. Worth testing because
 * nothing fails loudly when a file icon is wrong — the tree just shows the plain TOML icon, or
 * shows the Python chip on every `.toml` in the project, and either one looks like a design
 * decision rather than a bug.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class PyprojectIconProviderTest {

    private val fixture by codeInsightFixture()

    private val provider = PyprojectIconProvider()

    private fun file(name: String): VirtualFile =
        fixture.addFileToProject(name, "[project]\nname = \"x\"\n").virtualFile

    @Test
    fun `pyproject toml gets the pyproject icon`() {
        assertSame(
            BasedPythonIcons.PyprojectToml,
            provider.getIcon(file("pyproject.toml"), 0, fixture.project),
        )
    }

    /**
     * The registration, not the class: `IconUtil.computeFileIcon` is the platform's own path to a
     * file's icon, so reaching the icon through it is what proves the `fileIconProvider` entry in
     * plugin.xml names an extension point that exists and is consulted.
     */
    @Test
    fun `the platform's own icon lookup finds it`() {
        assertSame(
            BasedPythonIcons.PyprojectToml,
            IconUtil.computeFileIcon(file("pyproject.toml"), 0, fixture.project),
        )
    }

    /**
     * Every other `.toml` keeps the platform's icon. Answering here would put the Python chip on
     * `ruff.toml`, `uv.toml` and every unrelated manifest in the project.
     */
    @Test
    fun `other toml files are left alone`() {
        for (name in listOf("ruff.toml", "uv.toml", ".pyprojectx.toml", "Cargo.toml", "toml")) {
            val vfile = file(name)
            assertNull(provider.getIcon(vfile, 0, fixture.project), "$name should keep the platform's icon")
            assertNotSame(
                BasedPythonIcons.PyprojectToml,
                IconUtil.computeFileIcon(vfile, 0, fixture.project),
                "$name should not be given the pyproject icon by the platform's lookup either",
            )
        }
    }

    /** The name is what is matched, so a directory that happens to carry it must not be claimed. */
    @Test
    fun `a directory named pyproject toml is not a manifest`() {
        val directory = fixture.addFileToProject("pyproject.toml/inside.txt", "").virtualFile.parent
        assertNull(provider.getIcon(directory, 0, fixture.project))
    }
}
