package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Key
import com.intellij.platform.lsp.api.LspServerNotificationsHandler
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.runInEdtAndWait
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger

/**
 * `workspace/inlayHint/refresh` from `by` reaches the hints this plugin draws: the platform's handler
 * still hears it, and every editor of the project forgets that its hints are up to date, which is
 * what makes the inlay pass run again on the daemon restart that follows.
 */
@TestFixtures
class ByInlayRefreshTest {

    private val fixture by codeInsightFixture()

    /**
     * The platform's own key for the PSI stamp the inlay pass last ran against; private to it, so
     * found by name, once its class has made it.
     */
    @Suppress("DEPRECATION", "UNCHECKED_CAST")
    private val stamp: Key<Long> by lazy { Key.findKeyByName("inlay.psi.modification.stamp") as Key<Long> }

    @Test
    fun `a refresh from by is passed on, and every editor's hints are asked for again`() {
        lateinit var editor: Editor
        runInEdtAndWait {
            fixture.configureByText("shapes.by", "x = 1\n")
            editor = fixture.editor
            InlayHintsPassFactoryInternal.putCurrentModificationStamp(editor, fixture.file)
        }
        assertNotNull(editor.getUserData(stamp))

        val platformRefreshes = AtomicInteger()
        val platform = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(LspServerNotificationsHandler::class.java)) { _, method, _ ->
            if (method.name == "refreshInlayHints") platformRefreshes.incrementAndGet()
            CompletableFuture.completedFuture<Void>(null)
        } as LspServerNotificationsHandler

        ByInlayRefresh.Handler(fixture.project, platform).refreshInlayHints().get()
        assertEquals(1, platformRefreshes.get())

        val deadline = System.nanoTime() + 10_000_000_000
        while (editor.getUserData(stamp) != null && System.nanoTime() < deadline) {
            runInEdtAndWait { }
            Thread.sleep(10)
        }
        assertNull(editor.getUserData(stamp))
    }
}
