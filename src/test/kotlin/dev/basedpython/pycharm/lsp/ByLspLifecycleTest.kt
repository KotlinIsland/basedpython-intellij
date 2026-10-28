package dev.basedpython.pycharm.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.hints.codeVision.ModificationStampUtil
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.InitializeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A server's start and stop reach [ByLspLifecycleListener.TOPIC] in order, each followed by every
 * open file drawn again, and nothing does once the plugin's scope has ended: the platform reports a server stopping after the plugin is unloaded,
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

    @Test
    fun `once every subscriber has heard a start or a stop, every open file is drawn again`() {
        lateinit var editor: Editor
        lateinit var document: Document
        runInEdtAndWait {
            fixture.configureByText("shapes.by", "class Shape:\n    def area(self) -> float:\n        return 0.0\n")
            editor = fixture.editor
            document = editor.document
        }
        val daemon = DaemonCodeAnalyzer.getInstance(fixture.project) as DaemonCodeAnalyzerImpl
        for (event in listOf<(ByLspLifecycleListener.Broadcaster) -> Unit>({ it.serverInitialized(InitializeResult()) }, { it.serverStopped(false) })) {
            runInEdtAndWait {
                fixture.doHighlighting()
                ModificationStampUtil.putCurrentModificationStamp(editor, fixture.file)
            }
            assertTrue(daemon.fileStatusMap.allDirtyScopesAreNullFor(document), "highlighted, and nothing left to do")
            // what a subscriber dropping a server's answers sees: the editor not yet drawn again
            val drawnBeforeHeard = CopyOnWriteArrayList<Boolean>()
            val disposable = Disposer.newDisposable()
            Disposer.register(fixture.testRootDisposable, disposable)
            fixture.project.messageBus.connect(disposable).subscribe(ByLspLifecycleListener.TOPIC, object : ByLspLifecycleListener {
                override fun serverInitialized(serverName: String) {
                    drawnBeforeHeard += ModificationStampUtil.getModificationStamp(editor) == null
                }

                override fun serverStopped(serverName: String, shutdownNormally: Boolean) {
                    drawnBeforeHeard += ModificationStampUtil.getModificationStamp(editor) == null
                }
            })
            val job = Job()
            try {
                event(ByLspLifecycleListener.Broadcaster(ByLspLifecycle(fixture.project, CoroutineScope(job + Dispatchers.Default)), "by"))
                awaitCount(drawnBeforeHeard, 1)
                val deadline = System.nanoTime() + 10_000_000_000
                while (ModificationStampUtil.getModificationStamp(editor) != null && System.nanoTime() < deadline) Thread.sleep(10)
                assertEquals(listOf(false), drawnBeforeHeard.toList())
                assertNull(ModificationStampUtil.getModificationStamp(editor), "code vision's pass runs again too")
                assertFalse(daemon.fileStatusMap.allDirtyScopesAreNullFor(document), "the file is highlighted again")
            } finally {
                job.cancel()
                Disposer.dispose(disposable)
            }
        }
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

    private fun awaitCount(heard: List<*>, count: Int) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (heard.size < count && System.nanoTime() < deadline) Thread.sleep(10)
    }
}
