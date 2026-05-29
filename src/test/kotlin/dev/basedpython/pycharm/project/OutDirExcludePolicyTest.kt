package dev.basedpython.pycharm.project

import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import dev.basedpython.pycharm.settings.BasedPythonSettings

/**
 * Verifies [OutDirExcludePolicy] honours the [BasedPythonSettings.indexGeneratedPython]
 * toggle: by default `out/` is excluded (so `.by` stays the source of truth), but when
 * the user opts in to Python interop the directory is no longer excluded so a Python
 * plugin can index the generated `.py` files.
 */
class OutDirExcludePolicyTest : BasePlatformTestCase() {

    private lateinit var policy: OutDirExcludePolicy

    override fun setUp() {
        super.setUp()
        policy = OutDirExcludePolicy(project)
        // Start from the documented default.
        BasedPythonSettings.getInstance(project).indexGeneratedPython = false
    }

    fun `test out dir excluded by default`() {
        val urls = policy.excludeUrlsForProject
        assertTrue("at least one excluded url expected", urls.isNotEmpty())
    }

    fun `test base path out is among excluded urls`() {
        val expected = VfsUtilCore.pathToUrl("${project.basePath}/out")
        assertTrue("base out url should be present: $expected in ${policy.excludeUrlsForProject.toList()}",
            policy.excludeUrlsForProject.contains(expected))
    }

    fun `test every excluded url points at an out directory`() {
        val urls = policy.excludeUrlsForProject
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue("url should end with /out, was: $it", it.endsWith("/out")) }
    }

    fun `test excluded urls are deduplicated`() {
        val urls = policy.excludeUrlsForProject.toList()
        assertEquals("urls should be unique", urls.size, urls.toSet().size)
    }

    fun `test no exclusion when index generated python enabled`() {
        BasedPythonSettings.getInstance(project).indexGeneratedPython = true
        assertEquals(0, policy.excludeUrlsForProject.size)
    }

    fun `test toggling setting flips exclusion`() {
        val settings = BasedPythonSettings.getInstance(project)

        settings.indexGeneratedPython = false
        assertTrue(policy.excludeUrlsForProject.isNotEmpty())

        settings.indexGeneratedPython = true
        assertEquals(0, policy.excludeUrlsForProject.size)

        settings.indexGeneratedPython = false
        assertTrue(policy.excludeUrlsForProject.isNotEmpty())
    }

    fun `test repeated calls are stable`() {
        val first = policy.excludeUrlsForProject.toList()
        val second = policy.excludeUrlsForProject.toList()
        assertEquals(first, second)
    }

    fun `test outUrls helper includes content roots`() {
        // Helper used directly so multi-root logic is unit-covered even with a
        // single-root fixture.
        val urls = OutDirExcludePolicy.outUrls(project).toList()
        assertTrue(urls.isNotEmpty())
        urls.forEach { assertTrue(it.endsWith("/out")) }
        assertEquals(urls.size, urls.toSet().size)
    }
}
