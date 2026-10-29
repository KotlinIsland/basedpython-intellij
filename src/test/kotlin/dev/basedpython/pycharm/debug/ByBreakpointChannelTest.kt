package dev.basedpython.pycharm.debug

import com.intellij.icons.AllIcons
import com.intellij.platform.dap.DapDebugSession
import com.intellij.platform.dap.DapSessionContext
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * What a session's breakpoints say through bpd's own channel: exception classes and suspend
 * policies, in the `launch` and then as `bpd/setExceptionClasses` and `bpd/setSuspendPolicies`, and
 * each class's verdict on its breakpoint.
 */
class ByBreakpointChannelTest {

    /** Each request the adapter was sent: its command and arguments. */
    private val sent = mutableListOf<Pair<String, JsonObject>>()

    /** What the adapter answers a command with. */
    private var answer: (String, JsonObject) -> Result<Any?> = { _, _ -> Result.success(null) }

    /** Each breakpoint's presentation as the session was told it: icon and message. */
    private val shown = mutableMapOf<XBreakpoint<*>, Pair<Any?, String?>>()

    private val dapSession = proxy<DapDebugSession> { name, args ->
        if (name == "post") {
            @Suppress("UNCHECKED_CAST")
            val block = args[0] as suspend DapSessionContext.() -> Unit
            runBlocking {
                answeringContext(this) { command, arguments ->
                    sent += command to arguments as JsonObject
                    answer(command, arguments)
                }.block()
            }
        }
        null
    }

    private val session = proxy<XDebugSession> { name, args ->
        if (name == "updateBreakpointPresentation") shown[args[0] as XBreakpoint<*>] = args[1] to args[2] as String?
        null
    }

    private fun channel(backend: ByDebugBackend = ByDebugBackend.BPD) = ByBreakpointChannel(dapSession, session, backend)

    private fun classBreakpoint(name: String, raise: Boolean = false, terminate: Boolean = true, ignoreLibraries: Boolean = false) =
        ByExceptionBreakpointProperties().apply {
            exceptionClass = name
            notifyOnRaise = raise
            notifyOnTerminate = terminate
            this.ignoreLibraries = ignoreLibraries
        }.let { properties -> proxy<XBreakpoint<ByExceptionBreakpointProperties>> { n, _ -> if (n == "getProperties") properties else null } }

    private val lineBreakpoint = proxy<XLineBreakpoint<*>> { _, _ -> null }

    private fun rule(breakpoint: XBreakpoint<ByExceptionBreakpointProperties>) = breakpoint.properties!!.rule()

    @Test
    fun `a class breakpoint is bpd's rule, its library choice the inverse of ignore library files`() {
        assertEquals(
            ByBreakpointChannel.ClassRule("KeyError", raised = true, uncaught = false, libraries = false),
            rule(classBreakpoint("KeyError", raise = true, terminate = false, ignoreLibraries = true)),
        )
        // stopping on nothing is no rule, not bpd's `never`, which would hold the class back from the filters too
        assertNull(rule(classBreakpoint("KeyError", raise = false, terminate = false)))
        assertNull(ByExceptionBreakpointProperties().rule(), "Any exception is the platform's filters")
    }

    @Test
    fun `the launch carries every rule and every policy held before it, and nothing is sent until the session starts`() {
        val channel = channel()
        channel.setClass(classBreakpoint("KeyError"), rule(classBreakpoint("KeyError")))
        channel.setPolicy(lineBreakpoint, ByBreakpointChannel.Policy("/p/main.by", 3, "all"))
        assertEquals(
            mapOf(
                "exceptionClasses" to listOf(mapOf("name" to "KeyError", "raised" to false, "uncaught" to true, "libraries" to true)),
                "suspendPolicies" to listOf(mapOf("path" to "/p/main.by", "line" to 3, "suspend" to "all")),
            ),
            channel.launchArguments(),
        )
        assertTrue(sent.isEmpty(), "before the launch's answer, what is held goes in the launch alone")
    }

    @Test
    fun `once started, the whole of each set is sent, and every change sends it again`() {
        val channel = channel()
        channel.setPolicy(lineBreakpoint, ByBreakpointChannel.Policy("/p/main.by", 3, "all"))
        channel.started()
        assertEquals(listOf("bpd/setSuspendPolicies", "bpd/setExceptionClasses"), sent.map { it.first })
        sent.clear()
        channel.setPolicy(lineBreakpoint, null)
        assertEquals("bpd/setSuspendPolicies" to buildJsonObject { put("policies", buildJsonArray {}) }, sent.single())
    }

    @Test
    fun `each class's verdict is shown on its breakpoint`() {
        val verified = classBreakpoint("KeyError")
        val pending = classBreakpoint("json.JSONDecodeError")
        val failed = classBreakpoint("os.error")
        answer = { _, arguments ->
            Result.success(buildJsonObject {
                put("classes", buildJsonArray {
                    for (asked in arguments["classes"]!!.jsonArray) {
                        val name = asked.jsonObject["name"]!!.jsonPrimitive.content
                        add(buildJsonObject {
                            put("name", name)
                            when (name) {
                                "KeyError" -> put("verified", true)
                                "json.JSONDecodeError" -> { put("verified", false); put("reason", "pending"); put("message", "`json` is not imported") }
                                else -> { put("verified", false); put("reason", "failed"); put("message", "an alias of `OSError`") }
                            }
                        })
                    }
                })
            })
        }
        val channel = channel()
        for (breakpoint in listOf(verified, pending, failed)) channel.setClass(breakpoint, rule(breakpoint))
        channel.started()
        assertEquals(null to null, shown[verified])
        assertEquals("`json` is not imported", shown[pending]?.second)
        assertEquals(AllIcons.Debugger.Db_invalid_breakpoint to "an alias of `OSError`", shown[failed])
    }

    @Test
    fun `a bpd that refuses the requests has neither feature, and is asked no more`() {
        val breakpoint = classBreakpoint("KeyError")
        answer = { command, _ -> refusal("bpd's DAP adapter does not implement `$command`, …") }
        val channel = channel()
        channel.setClass(breakpoint, rule(breakpoint))
        channel.setPolicy(lineBreakpoint, ByBreakpointChannel.Policy("/p/main.by", 3, "thread"))
        channel.started()
        assertEquals(2, sent.size)
        assertEquals(AllIcons.Debugger.Db_invalid_breakpoint, shown[breakpoint]?.first)
        assertTrue(shown[breakpoint]?.second.orEmpty().contains("bpd/setExceptionClasses"))
        sent.clear()
        channel.setPolicy(lineBreakpoint, ByBreakpointChannel.Policy("/p/main.by", 3, "all"))
        channel.setClass(breakpoint, null)
        assertTrue(sent.isEmpty(), "sent $sent")
    }

    @Test
    fun `debugpy is sent nothing, and a class breakpoint says it needs bpd`() {
        val breakpoint = classBreakpoint("KeyError")
        val channel = channel(ByDebugBackend.DEBUGPY)
        channel.setClass(breakpoint, rule(breakpoint))
        channel.setPolicy(lineBreakpoint, ByBreakpointChannel.Policy("/p/main.by", 3, "all"))
        channel.started()
        assertEquals(emptyMap<String, Any>(), channel.launchArguments())
        assertTrue(sent.isEmpty())
        assertEquals(AllIcons.Debugger.Db_invalid_breakpoint, shown[breakpoint]?.first)
    }

    @Test
    fun `a breakpoint's Suspend setting is what its hit holds, and none only when the IDE need not hear of the hit`() {
        fun breakpoint(policy: SuspendPolicy, logMessage: Boolean = false, temporary: Boolean = false) =
            proxy<XLineBreakpoint<*>> { name, _ ->
                when (name) {
                    "getSuspendPolicy" -> policy
                    "isLogMessage" -> logMessage
                    "isLogStack", "isTemporary" -> if (name == "isTemporary") temporary else false
                    else -> null
                }
            }
        assertEquals("all", ByBreakpointChannel.suspendOf(breakpoint(SuspendPolicy.ALL)))
        assertEquals("thread", ByBreakpointChannel.suspendOf(breakpoint(SuspendPolicy.THREAD)))
        assertEquals("none", ByBreakpointChannel.suspendOf(breakpoint(SuspendPolicy.NONE)))
        // the IDE prints "Breakpoint hit" and removes a temporary one: it has to be told of the hit
        assertEquals("thread", ByBreakpointChannel.suspendOf(breakpoint(SuspendPolicy.NONE, logMessage = true)))
        assertEquals("thread", ByBreakpointChannel.suspendOf(breakpoint(SuspendPolicy.NONE, temporary = true)))
    }

    private inline fun <reified T> proxy(crossinline answer: (String, Array<Any?>) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { self, method, args ->
            when (method.name) {
                "equals" -> self === args!![0]
                "hashCode" -> System.identityHashCode(self)
                "toString" -> T::class.java.simpleName
                else -> answer(method.name, args ?: emptyArray()) ?: when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    else -> null
                }
            }
        } as T
}
