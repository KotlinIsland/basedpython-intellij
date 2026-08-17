package dev.basedpython.pycharm.run.marker

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import dev.basedpython.pycharm.run.main.ByRunWithArgumentsAction
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * What the gutter offers on a `def main`, which depends entirely on what that `main` takes.
 *
 * The icon itself has always been there; what is under test is the advice around it — an argument
 * form offered first when a plain run would die for want of a required argument, offered last when
 * the arguments are optional, and not offered at all when there is no generated command line to
 * fill in.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByRunLineMarkerContributorTest {

    private val fixture by codeInsightFixture()

    private var index = 0

    private data class Marker(val info: RunLineMarkerContributor.Info?, val element: PsiElement)

    /** The gutter's verdict on the first leaf of [source]'s first line. */
    private fun markerFor(source: String): Marker {
        // A fresh path per call: the fixture's project is shared across the tests in this class.
        val file = fixture.addFileToProject("pkg/main${index++}.by", source)
        val element = requireNotNull(file.findElementAt(0)) { "no leaf at the start of $source" }
        return Marker(ByRunLineMarkerContributor().getInfo(element), element)
    }

    private val Marker.tooltip: String get() = info?.tooltipProvider?.apply(element).orEmpty()

    private val Marker.offersArguments: Boolean
        get() = info?.actions.orEmpty().any { it is ByRunWithArgumentsAction }

    private val Marker.offersArgumentsFirst: Boolean
        get() = info?.actions?.firstOrNull() is ByRunWithArgumentsAction

    @Test
    fun `a main that requires an argument says so, and still leads with Run`() {
        // Run is never the wrong choice: the configuration asks for what it is missing as it
        // starts, so the form is here to *change* arguments, not to rescue a run that cannot go.
        val marker = markerFor("def main(a: int):\n    print(a)\n")
        assertNotNull(marker.info)
        assertTrue(marker.offersArguments)
        assertFalse(marker.offersArgumentsFirst)
        assertTrue(marker.tooltip.contains("requires a"), marker.tooltip)
    }

    @Test
    fun `a main whose arguments are all optional is offered the form too`() {
        val marker = markerFor("def main(name: str = \"world\"):\n    print(name)\n")
        assertTrue(marker.offersArguments)
        assertFalse(marker.offersArgumentsFirst)
        assertEquals("Run with by", marker.tooltip)
    }

    @Test
    fun `a main with no parameters is offered nothing extra`() {
        val marker = markerFor("def main():\n    print(1)\n")
        assertNotNull(marker.info)
        assertFalse(marker.offersArguments)
    }

    @Test
    fun `a main that is no entry point says so`() {
        // No `__main__` guard is generated for this one, so running the module does nothing at all.
        val marker = markerFor("def main(db: Database):\n    print(db)\n")
        assertFalse(marker.offersArguments)
        assertTrue(marker.tooltip.contains("not an entry point"), marker.tooltip)
    }

    @Test
    fun `a module that invokes main itself has no generated command line to fill`() {
        val marker = markerFor("def main(a: int):\n    print(a)\n\nmain(1)\n")
        assertNotNull(marker.info)
        assertFalse(marker.offersArguments)
    }

    @Test
    fun `the guard line keeps its plain icon`() {
        val marker = markerFor("if __name__ == \"__main__\":\n    main()\n")
        assertNotNull(marker.info)
        assertFalse(marker.offersArguments)
        assertEquals("Run with by", marker.tooltip)
    }

    /**
     * A plain `.py` is run by the interpreter exactly as written, so a bare `def main(…)` is a
     * function nothing calls — basedpython's generated guard and argument parser are a `.by` thing.
     * Marking it would offer to run a program that does nothing.
     */
    @Test
    fun `a bare def main in an owned py file gets no icon`() = asBasedPythonProject {
        assertNull(pyMarkerFor("def main(a: int):\n    print(a)\n").info)
    }

    /** The guard is Python's own entry point, so it is marked in a `.py` exactly as in a `.by`. */
    @Test
    fun `a guard in an owned py file is still an entry point`() = asBasedPythonProject {
        val marker = pyMarkerFor("if __name__ == \"__main__\":\n    main()\n")
        assertNotNull(marker.info, "the __main__ guard is an entry point in a .py too")
        assertFalse(marker.offersArguments)
    }

    /** A `.py` this plugin does not own belongs to the Python plugin, icons included. */
    @Test
    fun `an unowned py file gets no icon at all`() {
        assertNull(pyMarkerFor("if __name__ == \"__main__\":\n    main()\n").info)
    }

    /** [markerFor]'s `.py` twin. */
    private fun pyMarkerFor(source: String): Marker {
        val file = fixture.addFileToProject("pkg/plain${index++}.py", source)
        val element = requireNotNull(file.findElementAt(0)) { "no leaf at the start of $source" }
        return Marker(ByRunLineMarkerContributor().getInfo(element), element)
    }

    /**
     * Runs [body] with the project marked basedpython and `.py` pinned to this plugin, then puts
     * both back. Pinned rather than left on AUTO so the outcome does not depend on whether the IDE
     * running the tests happens to provide the Python language.
     */
    private fun asBasedPythonProject(body: () -> Unit) {
        val settings = BasedPythonSettings.getInstance(fixture.project)
        val handling = settings.pyFileHandling
        val enabled = settings.byEnabled
        val marker = Paths.get(fixture.project.basePath!!)
            .also { Files.createDirectories(it) }
            .resolve("api.lock")
        val created = !Files.exists(marker)
        if (created) Files.createFile(marker)
        settings.byEnabled = true
        settings.pyFileHandling = PyFileHandling.ALWAYS
        try {
            body()
        } finally {
            settings.pyFileHandling = handling
            settings.byEnabled = enabled
            if (created) Files.deleteIfExists(marker)
        }
    }
}
