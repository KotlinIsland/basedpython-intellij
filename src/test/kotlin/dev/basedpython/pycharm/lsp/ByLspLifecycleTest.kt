package dev.basedpython.pycharm.lsp

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.InitializeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A server's start and stop reach [ByLspLifecycleListener.TOPIC] in order, and nothing does once
 * the plugin's scope has ended: the platform reports a server stopping after the plugin is unloaded,
 * and a publish then put the topic's publisher back into the project's message bus, holding the
 * plugin's class loader.
 */
@TestFixtures
class ByLspLifecycleTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `starts and stops reach the topic in the order the servers reported them`() {
        val heard = listen()
        val job = Job()
        val broadcaster = ByLspLifecycleListener.Broadcaster(ByLspLifecycle(fixture.project, CoroutineScope(job + Dispatchers.Default)), "by")
        try {
            repeat(50) {
                broadcaster.serverInitialized(InitializeResult())
                broadcaster.serverStopped(it % 2 == 0)
            }
            awaitCount(heard, 100)
            assertEquals(List(50) { listOf("initialized by", "stopped by ${it % 2 == 0}") }.flatten(), heard.toList())
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `nothing is published once the plugin's scope has ended`() {
        val heard = listen()
        val job = Job()
        val broadcaster = ByLspLifecycleListener.Broadcaster(ByLspLifecycle(fixture.project, CoroutineScope(job + Dispatchers.Default)), "by")
        runBlocking { job.cancelAndJoin() }

        broadcaster.serverStopped(true)

        Thread.sleep(300)
        assertEquals(emptyList<String>(), heard.toList())
    }

    private fun listen(): MutableList<String> {
        val heard = CopyOnWriteArrayList<String>()
        val disposable = Disposer.newDisposable()
        Disposer.register(fixture.testRootDisposable, disposable)
        fixture.project.messageBus.connect(disposable).subscribe(ByLspLifecycleListener.TOPIC, object : ByLspLifecycleListener {
            override fun serverInitialized(serverName: String) {
                heard += "initialized $serverName"
            }

            override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                heard += "stopped $serverName $shutdownNormally"
            }
        })
        return heard
    }

    private fun awaitCount(heard: List<String>, count: Int) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (heard.size < count && System.nanoTime() < deadline) Thread.sleep(10)
    }
}
