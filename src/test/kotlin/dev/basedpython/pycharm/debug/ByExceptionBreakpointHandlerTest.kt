package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapBreakpointManager
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapExceptionBreakpoint
import com.intellij.platform.dap.DapSessionContext
import com.intellij.xdebugger.breakpoints.XBreakpoint
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * What [ByExceptionBreakpointHandler] leaves in the platform's DAP breakpoint manager as the
 * breakpoint is edited.
 *
 * The manager here keeps its exception breakpoints the way `DapBreakpointManagerImpl` does — a
 * `HashSet` it adds to and removes from, so a removal only lands on an *equal* breakpoint, and
 * equality is filter and condition. The platform unregisters an edited breakpoint after the
 * Breakpoints dialog has already written the new flags, which is what each test reproduces.
 */
class ByExceptionBreakpointHandlerTest {

    private val properties = ByExceptionBreakpointProperties()

    private val breakpoint = proxy<XBreakpoint<ByExceptionBreakpointProperties>> { _, name, _ ->
        if (name == "getProperties") properties else null
    }

    /** The manager's set, as `DapBreakpointManagerImpl.exceptionBreakpoints` holds it. */
    private val sent = HashSet<DapExceptionBreakpoint>()

    private val manager = proxy<DapBreakpointManager> { _, name, args ->
        when (name) {
            "addExceptionBreakpoint" -> sent.add(args[1] as DapExceptionBreakpoint)
            "removeExceptionBreakpoint" -> sent.remove(args[1] as DapExceptionBreakpoint)
        }
        Unit
    }

    /** Runs each command to completion as it is posted, which is the order the platform keeps. */
    private val session = proxy<DapDebugSession> { _, name, args ->
        when (name) {
            "post" -> {
                @Suppress("UNCHECKED_CAST")
                val block = args[0] as suspend DapSessionContext.() -> Unit
                runBlocking { answeringContext(this) { _, _ -> Result.success(null) }.block() }
            }
            "getBreakpointManager" -> manager
            else -> null
        }
    }

    private val handler = ByExceptionBreakpointHandler(session)

    private fun filters(): Set<String?> = sent.map { it.filter }.toSet()

    @Test
    fun `registering sends one breakpoint per filter`() {
        properties.notifyOnRaise = true
        handler.registerBreakpoint(breakpoint)
        assertEquals(setOf("raised", "uncaught"), filters())
    }

    /** The bug: unticking "on raise" removed only `uncaught`, and `raised` kept stopping. */
    @Test
    fun `unticking a flag stops sending it`() {
        properties.notifyOnRaise = true
        handler.registerBreakpoint(breakpoint)

        properties.notifyOnRaise = false
        handler.unregisterBreakpoint(breakpoint, false)
        handler.registerBreakpoint(breakpoint)

        assertEquals(setOf("uncaught"), filters())
    }

    @Test
    fun `unregistering after the flags changed removes what was sent`() {
        handler.registerBreakpoint(breakpoint)
        properties.notifyOnTerminate = false
        properties.notifyOnRaise = true
        handler.unregisterBreakpoint(breakpoint, false)
        assertEquals(emptySet<String?>(), filters())
    }

    /** A second registration without an unregistration in between must not leave the first behind. */
    @Test
    fun `registering again replaces what was sent`() {
        properties.notifyOnRaise = true
        handler.registerBreakpoint(breakpoint)
        properties.notifyOnRaise = false
        handler.registerBreakpoint(breakpoint)
        assertEquals(setOf("uncaught"), filters())
    }

    @Test
    fun `unregistering what was never registered sends nothing`() {
        handler.unregisterBreakpoint(breakpoint, false)
        assertEquals(emptySet<String?>(), filters())
    }

    private inline fun <reified T> proxy(crossinline answer: (Any, String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args!![0]
                "hashCode" -> System.identityHashCode(self)
                "toString" -> T::class.java.simpleName
                else -> answer(self, method.name, args ?: emptyArray())
            }
        } as T
}
