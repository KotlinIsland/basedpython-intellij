package dev.basedpython.pycharm.debug

import com.intellij.execution.console.LanguageConsoleImpl
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebugSession
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The basedpython prompt under a `.by` debug session's console: typed in basedpython, and evaluated in
 * a stopped frame only.
 */
@TestFixtures
class ByDebugPromptTest {

    private val fixture by codeInsightFixture()

    /** A session that is running: it has no frame to evaluate in. */
    private fun runningSession(): XDebugSession =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(XDebugSession::class.java)) { _, method, _ ->
            when (method.name) {
                "getProject" -> fixture.project
                else -> null
            }
        } as XDebugSession

    @Test
    fun `the prompt is typed in basedpython and says when there is no frame to evaluate in`() = onEdt {
        val prompt = ByDebugPrompt(runningSession(), CoroutineScope(Dispatchers.Default))
        try {
            assertEquals(BasedPythonLanguage, prompt.console.language)
            // a console prints into its editor, which is made with its component
            prompt.console.component
            prompt.evaluate("t * 2")
            val console = prompt.console as LanguageConsoleImpl
            console.flushDeferredText()
            assertTrue(console.historyViewer.document.text.contains("stopped frame"), console.historyViewer.document.text)
        } finally {
            Disposer.dispose(prompt)
        }
    }
}
