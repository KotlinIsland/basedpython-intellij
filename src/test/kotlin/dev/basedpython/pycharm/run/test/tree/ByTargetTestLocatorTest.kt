package dev.basedpython.pycharm.run.test.tree

import com.intellij.execution.Location
import com.intellij.execution.Platform
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.target.ByTargetPaths
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * A test run on a run target is reported in the target's paths — `by`'s reporter wrote
 * `file:///opt/project/tests/test_shapes.by:4` from a Docker target (measured) — and its tree still
 * opens the project's file at that line.
 */
@TestFixtures
class ByTargetTestLocatorTest {

    private val fixture by codeInsightFixture()

    private fun locate(path: String): List<Location<*>> {
        val file = fixture.addFileToProject("tests/test_shapes.by", "\n\n\ndef test_area():\n    assert True\n")
        val localRoot = Path.of(file.virtualFile.parent.parent.path)
        val paths = ByTargetPaths(localRoot, "/opt/project", Platform.UNIX)
        return ByTargetTestLocator(paths::toLocal)
            .getLocation("file", path, fixture.project, GlobalSearchScope.allScope(fixture.project))
    }

    @Test
    fun `the target's path of a test is the project's file, at its line`() = onEdt {
        val location = locate("/opt/project/tests/test_shapes.by:4").single()
        assertEquals("test_shapes.by", location.virtualFile!!.name)
        val document = fixture.getDocument(location.psiElement.containingFile)
        assertEquals(3, document.getLineNumber(location.psiElement.textOffset))
    }

    @Test
    fun `a path outside the project's copy on the target opens nothing`() = onEdt {
        assertTrue(locate("/usr/lib/python3/site-packages/x.py:4").isEmpty())
    }
}
