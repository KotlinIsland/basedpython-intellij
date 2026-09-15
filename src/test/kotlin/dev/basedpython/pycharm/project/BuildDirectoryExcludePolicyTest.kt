package dev.basedpython.pycharm.project

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.build.ByBuildOutputs
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [BuildDirectoryExcludePolicy] excludes what `by` said the build directories are, and nothing of
 * its own invention.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BuildDirectoryExcludePolicyTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private val outputs get() = ByBuildOutputs.getInstance(project)

    @BeforeEach
    fun setUp() {
        BasedPythonSettings.getInstance(project).indexGeneratedPython = false
    }

    @AfterEach
    fun tearDown() {
        outputs.loadState(ByBuildOutputs.Directories())
    }

    private fun answered(vararg paths: String) {
        outputs.loadState(ByBuildOutputs.Directories().apply { this.paths = paths.toMutableList() })
    }

    @Test
    fun `a project by has never answered for excludes nothing`() {
        // Not an `out/` per content root, and not a guessed `build/` either: the old policy excluded
        // a directory in every project the IDE opened, basedpython or not.
        assertEquals(emptyList<String>(), BuildDirectoryExcludePolicy(project).excludeUrlsForProject.toList())
    }

    @Test
    fun `the build directories by reported are excluded`() {
        answered("/work/app/build", "/work/lib/build")
        assertEquals(
            listOf(VfsUtilCore.pathToUrl("/work/app/build"), VfsUtilCore.pathToUrl("/work/lib/build")),
            BuildDirectoryExcludePolicy(project).excludeUrlsForProject.toList(),
        )
    }

    @Test
    fun `nothing is excluded when the generated python is wanted in the index`() {
        answered("/work/app/build")
        BasedPythonSettings.getInstance(project).indexGeneratedPython = true
        assertEquals(0, BuildDirectoryExcludePolicy(project).excludeUrlsForProject.size)
    }
}
