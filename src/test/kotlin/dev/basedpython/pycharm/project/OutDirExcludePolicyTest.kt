package dev.basedpython.pycharm.project

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Verifies [OutDirExcludePolicy] honours the [BasedPythonSettings.indexGeneratedPython]
 * toggle: by default `out/` is excluded (so `.by` stays the source of truth), but when
 * the user opts in to Python interop the directory is no longer excluded so a Python
 * plugin can index the generated `.py` files.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class OutDirExcludePolicyTest {

    private val fixture by codeInsightFixture()

    private val project get() = fixture.project

    private lateinit var policy: OutDirExcludePolicy

    @BeforeEach
    fun setUp() {
        policy = OutDirExcludePolicy(project)
        // Start from the documented default.
        BasedPythonSettings.getInstance(project).indexGeneratedPython = false
    }

    @Test
    fun `out dir excluded by default`() {
        val urls = policy.excludeUrlsForProject
        assertTrue(urls.isNotEmpty(), "at least one excluded url expected")
    }

    @Test
    fun `base path out is among excluded urls`() {
        val expected = VfsUtilCore.pathToUrl("${project.basePath}/out")
        assertTrue(
            policy.excludeUrlsForProject.contains(expected),
            "base out url should be present: $expected in ${policy.excludeUrlsForProject.toList()}",
        )
    }

    @Test
    fun `every excluded url points at an out directory`() {
        val urls = policy.excludeUrlsForProject
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue(it.endsWith("/out"), "url should end with /out, was: $it") }
    }

    @Test
    fun `excluded urls are deduplicated`() {
        val urls = policy.excludeUrlsForProject.toList()
        assertEquals(urls.size, urls.toSet().size, "urls should be unique")
    }

    @Test
    fun `no exclusion when index generated python enabled`() {
        BasedPythonSettings.getInstance(project).indexGeneratedPython = true
        assertEquals(0, policy.excludeUrlsForProject.size)
    }

    @Test
    fun `toggling setting flips exclusion`() {
        val settings = BasedPythonSettings.getInstance(project)

        settings.indexGeneratedPython = false
        assertTrue(policy.excludeUrlsForProject.isNotEmpty())

        settings.indexGeneratedPython = true
        assertEquals(0, policy.excludeUrlsForProject.size)

        settings.indexGeneratedPython = false
        assertTrue(policy.excludeUrlsForProject.isNotEmpty())
    }

    @Test
    fun `repeated calls are stable`() {
        val first = policy.excludeUrlsForProject.toList()
        val second = policy.excludeUrlsForProject.toList()
        assertEquals(first, second)
    }

    @Test
    fun `outUrls helper includes content roots`() {
        // Helper used directly so multi-root logic is unit-covered even with a
        // single-root fixture.
        val urls = OutDirExcludePolicy.outUrls(project).toList()
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue(it.endsWith("/out")) }
        assertEquals(urls.size, urls.toSet().size)
    }
}
