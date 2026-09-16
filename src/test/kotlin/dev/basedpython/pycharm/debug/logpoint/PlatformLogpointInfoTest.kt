package dev.basedpython.pycharm.debug.logpoint

import com.intellij.xdebugger.breakpoints.SuspendPolicy
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** What [PlatformLogpointInfo] puts on the info a log point is created from. */
class PlatformLogpointInfoTest {

    @Test
    fun `the expression reaches the info with its language`() {
        val info = PlatformLogpointInfo.of(
            SuspendPolicy.NONE,
            ByLogpoints.expressionOf("x * 2"),
        )

        assertEquals(SuspendPolicy.NONE, info.suspendPolicy)
        val expression = info.logExpressionIfEnabled
        assertNotNull(expression, "the log point would have nothing to log")
        assertEquals("x * 2", expression!!.expression)
        // the language is what makes the expression edit as basedpython rather than as plain text
        assertEquals(BasedPythonLanguage, expression.language)
    }

    @Test
    fun `a log point with nothing to log yet carries no expression`() {
        val info = PlatformLogpointInfo.of(SuspendPolicy.NONE)

        assertNull(info.logExpressionIfEnabled, "Add Log Point makes an empty log point")
    }
}
