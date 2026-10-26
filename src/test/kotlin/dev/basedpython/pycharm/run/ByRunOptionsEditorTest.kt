package dev.basedpython.pycharm.run

import com.intellij.execution.InputRedirectAware
import com.intellij.openapi.util.JDOMUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.jdom.Element
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The `by run` configuration's `.env` files, input redirection and terminal emulation: kept as the
 * rest of its options are, in the platform's fragments editor, and turned into the run's environment
 * and input.
 */
@TestFixtures
class ByRunOptionsEditorTest {

    private val fixture by codeInsightFixture()

    private fun configuration() = ByRunConfiguration(fixture.project, BasedPythonRunConfigurationType().runFactory, "run")

    @Test
    fun `the env files, the input and the terminal survive being written and read back`() = onEdt {
        val original = configuration().apply {
            envFilePaths = listOf("/p/.env", "/p/local.env")
            inputRedirectOptions.isRedirectInput = true
            inputRedirectOptions.redirectInputPath = "in.txt"
            options.emulateTerminal = true
        }
        val element = Element("configuration").also(original::writeExternal)
        val restored = configuration().also { it.readExternal(JDOMUtil.load(JDOMUtil.write(element))) }

        assertEquals(listOf("/p/.env", "/p/local.env"), restored.envFilePaths)
        assertTrue(restored.options.redirectInput)
        assertEquals("in.txt", restored.options.inputFile)
        assertTrue(restored.options.emulateTerminal)
        assertEquals(listOf("/p/.env", "/p/local.env"), (original.clone() as ByRunConfiguration).envFilePaths)
    }

    @Test
    fun `a relative input file is read from the working directory, and none when input is not redirected`() = onEdt {
        val c = configuration().apply {
            options.workingDir = "/work"
            options.inputFile = "in.txt"
        }
        assertNull(c.inputFile())
        c.options.redirectInput = true
        assertEquals(File("/work", "in.txt"), c.inputFile())
        c.options.inputFile = "/abs/in.txt"
        assertEquals(File("/abs/in.txt"), InputRedirectAware.getInputFile(c))
        assertEquals(File("/abs/in.txt"), c.inputFile())
    }

    @Test
    fun `variables from env files are beneath the configuration's own, on a run target too`() {
        val options = ByRunOptions().apply { envVars = linkedMapOf("OVERRIDE" to "explicit") }
        val environment = byTargetEnvironment(options, "/w", startsProgram = false, separator = ':', fileEnvironment = mapOf("OVERRIDE" to "file", "FROM_ENV" to "hello"))
        assertEquals("explicit", environment["OVERRIDE"])
        assertEquals("hello", environment["FROM_ENV"])
    }

    @Test
    fun `the editor shows the fields a run needs and offers the rest`() = onEdt {
        val editor = ByRunFragmentedEditor(configuration())
        try {
            // built as the dialog builds it, which is what makes the fragments the editor's to dispose
            editor.component
            val ids = editor.javaClass.superclass.superclass.getDeclaredMethod("getFragments").let { it.isAccessible = true; it.invoke(editor) as Collection<*> }
                .map { (it as com.intellij.execution.ui.SettingsEditorFragment<*, *>).id }
            for (id in listOf("by.module", "by.programArgs", "by.environment", "workingDirectory", "environmentVariables", "by.redirectInput", "by.emulateTerminal", "beforeRunTasks")) {
                assertTrue(id in ids, "$id is not a fragment: $ids")
            }
        } finally {
            com.intellij.openapi.util.Disposer.dispose(editor)
        }
    }
}
