package dev.basedpython.pycharm.debug.logpoint

import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpointAdditionalInfo
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That the IDE this is built against still has a setter [PlatformLogpointInfo] can use.
 *
 * The one thing that object can get wrong, and the one thing nothing else would notice. It looks the
 * builder's log-expression setter up by parameter type at runtime, so a platform that changes that
 * type again compiles perfectly and throws in a user's editor — which is exactly how the 262 to 263
 * change was found, as a `NoSuchMethodError` out of the `print` quick fix with the `print` already
 * deleted.
 *
 * This asks the class file the same question the code asks, so the next such change is a failing
 * build in the release that introduces it rather than a quick fix that eats a line of code.
 */
class PlatformLogpointInfoTest {

    private val builder = XLineBreakpointAdditionalInfo.Builder::class.java

    @Test
    fun `the 2026_3 setter is found, or the 2026_2 one is`() {
        val new = builder.has(XExpression::class.java)
        val old = builder.has(String::class.java)

        assertTrue(
            new || old,
            "${builder.name} has neither setLogExpressionIfEnabled PlatformLogpointInfo knows, so a " +
                "log point would be created without its expression. Its methods are now: " +
                builder.methods.joinToString { it.toGenericString() },
        )
    }

    @Test
    fun `the expression reaches the info this IDE builds`() {
        val info = PlatformLogpointInfo.of(
            XLineBreakpointVerticalPlacement.INTER_LINE,
            SuspendPolicy.NONE,
            ByLogpoints.expressionOf("x * 2"),
        )

        assertEquals(XLineBreakpointVerticalPlacement.INTER_LINE, info.verticalPlacement)
        assertEquals(SuspendPolicy.NONE, info.suspendPolicy)
        // `logExpressionIfEnabled` is a `String` on 262 and an `XExpression` on 263, which is the
        // whole reason this object exists; what both builds have to agree on is that it says `x * 2`.
        assertNotNull(info.logExpressionIfEnabled, "the log point would have nothing to log")
        assertEquals("x * 2", info.logExpressionIfEnabled.let { it as? String ?: (it as XExpression).expression })
    }

    @Test
    fun `a log point with nothing to log yet carries no expression`() {
        val info = PlatformLogpointInfo.of(XLineBreakpointVerticalPlacement.INTER_LINE, SuspendPolicy.NONE)

        assertNull(info.logExpressionIfEnabled, "a click in the gutter gap makes an empty log point")
    }

    private fun Class<*>.has(vararg types: Class<*>) =
        try {
            getMethod("setLogExpressionIfEnabled", *types)
            true
        } catch (_: NoSuchMethodException) {
            false
        }
}
