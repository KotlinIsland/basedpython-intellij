package dev.basedpython.pycharm.debug

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.dap.DapEvaluationContext
import com.intellij.platform.dap.DapEvaluationResult
import com.intellij.platform.dap.DapScope
import com.intellij.platform.dap.DapSessionContext
import com.intellij.platform.dap.DapSessionExecutor
import com.intellij.platform.dap.DapStackFrame
import com.intellij.platform.dap.DapThread
import com.intellij.platform.dap.StackFrameType
import com.intellij.platform.dap.TextPosition
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.SimpleColoredText
import com.intellij.xdebugger.XSourcePosition
import com.jetbrains.dap.protocol.FrameId
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * A frame at line 0 is one at no line — DAP's lines start at 1, and bpd reports code the transpiler
 * wrote with no line of its own as line 0, its source a name with no path — and the Frames view
 * says so, and never goes to it.
 */
@TestFixtures
class ByNoLineFrameTest {

    private val fixture by codeInsightFixture()

    private inline fun <reified T> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("${method.name} is not called to present a frame")
        } as T

    private fun frame(source: VirtualFile?, line: Int) = object : DapStackFrame {
        override val id: FrameId get() = FrameId(7)
        override val name: String = "_LazyFrom.__getattr__"
        override val type: StackFrameType = StackFrameType.Normal
        override val thread: DapThread get() = unused()
        override val source: VirtualFile? = source
        override val startPosition: TextPosition = TextPosition(line, if (line == 0) 0 else 1)
        override val endPosition: TextPosition = startPosition
        override suspend fun DapSessionContext.scopes(): List<DapScope> = emptyList()
        override suspend fun DapSessionContext.evaluate(expression: String): DapEvaluationResult = error("not evaluated")
        override suspend fun DapSessionContext.evaluate(expression: String, context: DapEvaluationContext): DapEvaluationResult =
            error("not evaluated")
    }

    private fun presented(frame: DapStackFrame): Pair<String, XSourcePosition?> {
        val factory = ByPresentationSupport(fixture.project) { ByDebugBackend.BPD }.createPresentationFactory(null)
        val shown = factory.createStackFrame(unused<DapSessionExecutor>(), unused<DapThread>(), frame)
        val text = SimpleColoredText().also { shown.customizePresentation(it) }.toString()
        return text to shown.sourcePosition
    }

    @Test
    fun `a frame with no line and no path says it has no line, and has no position`() = onEdt {
        val (text, position) = presented(frame(source = null, line = 0))
        assertEquals("_LazyFrom.__getattr__, no line", text)
        assertNull(position)
    }

    @Test
    fun `a frame at line 0 of a file names the file, says it has no line, and is not put at its top`() = onEdt {
        val file = fixture.addFileToProject("main.by", "x = 1\n").virtualFile
        val (text, position) = presented(frame(source = file, line = 0))
        assertEquals("_LazyFrom.__getattr__, main.by: no line", text)
        assertNull(position)
    }

    @Test
    fun `a frame at a line is the platform's`() = onEdt {
        val file = fixture.addFileToProject("main.by", "x = 1\ny = 2\n").virtualFile
        val (text, position) = presented(frame(source = file, line = 2))
        assertEquals("_LazyFrom.__getattr__, main.by:2:1", text)
        assertNotNull(position)
        assertEquals(1, position!!.line)
    }
}
