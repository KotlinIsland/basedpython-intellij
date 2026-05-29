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
        assertEquals("exactly one excluded url expected", 1, urls.size)
        val expected = VfsUtilCore.pathToUrl("${project.basePath}/out")
        assertEquals(expected, urls[0])
    }

    fun `test excluded url points at out directory`() {
        val url = policy.excludeUrlsForProject.single()
        assertTrue("url should end with /out, was: $url", url.endsWith("/out"))
    }

    fun `test no exclusion when index generated python enabled`() {
        BasedPythonSettings.getInstance(project).indexGeneratedPython = true
        assertEquals(0, policy.excludeUrlsForProject.size)
    }

    fun `test toggling setting flips exclusion`() {
        val settings = BasedPythonSettings.getInstance(project)

        settings.indexGeneratedPython = false
        assertEquals(1, policy.excludeUrlsForProject.size)

        settings.indexGeneratedPython = true
        assertEquals(0, policy.excludeUrlsForProject.size)

        settings.indexGeneratedPython = false
        assertEquals(1, policy.excludeUrlsForProject.size)
    }

    fun `test repeated calls are stable`() {
        val first = policy.excludeUrlsForProject.toList()
        val second = policy.excludeUrlsForProject.toList()
        assertEquals(first, second)
    }
}
