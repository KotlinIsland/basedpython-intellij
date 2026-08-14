package dev.basedpython.pycharm.run.marker

import com.intellij.execution.lineMarker.RunLineMarkerContributor
import com.intellij.psi.PsiElement
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.run.main.ByRunWithArgumentsAction
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
