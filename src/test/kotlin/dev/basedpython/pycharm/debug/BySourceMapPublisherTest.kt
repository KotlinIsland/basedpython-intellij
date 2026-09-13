package dev.basedpython.pycharm.debug

import com.intellij.platform.dap.DapCommandProcessor
import com.intellij.platform.dap.DapEventConsumer
import org.eclipse.lsp4j.debug.OutputEventArguments
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The consumer [BySourceMapPublisher] hands the platform forwards every event it does not handle.
 *
 * Driven by reflection over whatever `DapEventConsumer` is on the classpath rather than by a list of
 * its methods, because a list is what went wrong: 263 added `invalidated`, and a forwarder written
 * against 262 had nothing to forward it with. Run against a newer platform, this covers the newer
 * methods without being edited.
 */
class BySourceMapPublisherTest {

    private val calls = mutableListOf<Pair<String, List<Any?>>>()

    private val delegate = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(DapEventConsumer::class.java),
    ) { _, method, args ->
        if (method.declaringClass == Any::class.java) return@newProxyInstance null
        if (method.name == "output") throw IllegalStateException("from the delegate")
        calls += method.name to (args?.toList() ?: emptyList())
        null
    } as DapEventConsumer

    private val submitted = mutableListOf<Any>()

    private val commandProcessor = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(DapCommandProcessor::class.java),
    ) { _, method, args ->
        if (method.name == "submitCommand") submitted += args!![0]
        null
    } as DapCommandProcessor

    private val consumer = BySourceMapPublisher(delegate, commandProcessor, emptyList()).consumer

    private fun events(): List<Method> =
        DapEventConsumer::class.java.methods.filter { it.name != "initialized" && it.name != "output" }

    @Test
    fun `every event but initialized reaches the platform's consumer with its argument`() {
        val events = events()
        for (event in events) {
            val argument = event.parameterTypes.map { it.getConstructor().newInstance() }
            event.invoke(consumer, *argument.toTypedArray())
            val (name, received) = calls.last()
            assertEquals(event.name, name)
            assertEquals(argument.size, received.size)
            argument.zip(received).forEach { (sent, got) -> assertSame(sent, got) }
        }
        assertEquals(events.size, calls.size)
    }

    @Test
    fun `initialized is held back for the source maps rather than forwarded`() {
        consumer.initialized()
        assertEquals(emptyList<Pair<String, List<Any?>>>(), calls)
        assertEquals(1, submitted.size)
    }

    @Test
    fun `a failure in the platform's consumer surfaces as itself`() {
        val e = assertThrows(IllegalStateException::class.java) {
            consumer.output(OutputEventArguments())
        }
        assertEquals("from the delegate", e.message)
    }
}
