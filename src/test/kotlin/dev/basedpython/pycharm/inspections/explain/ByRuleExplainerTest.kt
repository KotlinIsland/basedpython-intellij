package dev.basedpython.pycharm.inspections.explain

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByRuleExplanation
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What *Explain Rule* tells the user, from what each server's request came back with.
 *
 * Each server declines a rule it does not own with an empty answer, so the one thing to get right is
 * keeping that apart from a server that could not be asked: a failure is not a rule nobody explains.
 */
@TestFixtures
class ByRuleExplainerTest {

    private val fixture by codeInsightFixture()

    private fun client(running: Boolean = true, answer: () -> java.util.concurrent.CompletableFuture<*>) =
        AnsweringClient(fixture.project, running) { answer() }

    private fun explanation(text: String) = ByRuleExplanation(name = "rule", summary = "", documentation = text)

    private val declines get() = client { answered(null) }

    @Test
    fun `buff's explanation is shown`() = onEdt {
        val result = ByRuleExplainer.explain("F401", buff = client { answered(explanation("unused")) }, by = declines)
        assertEquals(ByRuleExplanationResult.Found("unused"), result)
    }

    @Test
    fun `a rule buff declines is asked of by`() = onEdt {
        val result = ByRuleExplainer.explain("x", buff = declines, by = client { answered(explanation("by's")) })
        assertEquals(ByRuleExplanationResult.Found("by's"), result)
    }

    @Test
    fun `a rule both decline has no explanation`() = onEdt {
        val result = ByRuleExplainer.explain("nope", buff = declines, by = declines)
        assertEquals(ByRuleExplanationResult.NotFound(BasedPythonBundle.message("explainRule.noExplanation")), result)
    }

    @Test
    fun `buff answering with an error is not buff declining`() = onEdt {
        val buff = client { failed(ResponseErrorCode.InternalError, "request handler panicked") }
        val result = ByRuleExplainer.explain("F401", buff = buff, by = declines)
        assertEquals(ByRuleExplanationResult.NotFound(BasedPythonBundle.message("explainRule.serverDidNotAnswer")), result)
    }

    @Test
    fun `a buff that is not running is not buff declining either`() = onEdt {
        val result = ByRuleExplainer.explain("F401", buff = client(running = false) { answered(null) }, by = declines)
        assertEquals(ByRuleExplanationResult.NotFound(BasedPythonBundle.message("explainRule.serverDidNotAnswer")), result)
    }

    @Test
    fun `one server failing does not hide the other's explanation`() = onEdt {
        val buff = client { failed(ResponseErrorCode.InternalError) }
        val result = ByRuleExplainer.explain("x", buff = buff, by = client { answered(explanation("by's")) })
        assertEquals(ByRuleExplanationResult.Found("by's"), result)
    }

    @Test
    fun `an edit landing mid-request is asked again`() = onEdt {
        var asked = 0
        val by = client { if (++asked == 1) failed(ResponseErrorCode.ContentModified) else answered(explanation("by's")) }
        assertEquals(ByRuleExplanationResult.Found("by's"), ByRuleExplainer.explain("x", buff = null, by = by))
        assertEquals(2, asked)
    }

    @Test
    fun `no server at all says so`() = onEdt {
        val result = ByRuleExplainer.explain("x", buff = null, by = null)
        assertEquals(ByRuleExplanationResult.NotFound(BasedPythonBundle.message("explainRule.noServer")), result)
    }
}
