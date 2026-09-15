package dev.basedpython.pycharm.docs.render

import com.intellij.openapi.components.service
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByLspLifecycleListener
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.letContentHashingFinish
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When [ByRenderedDocsRefresher] reparses a file, which is a write action and a daemon restart, so
 * the question is as much when it must not as when it must.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByRenderedDocsRefresherTest {

    private val fixture by codeInsightFixture()

    private val refresher get() = fixture.project.service<ByRenderedDocsRefresher>()

    @AfterEach
    fun waitForHashing() = letContentHashingFinish()

    @Test
    fun `a file the server has not answered for is stale`() {
        val file = fixture.configureByText("a.by", "x = 1\n")
        assertNull(ByDocstringSpans.recorded(file))
        assertTrue(refresher.isStale(file.virtualFile))
    }

    @Test
    fun `a file the server says has no docstrings is not stale`() {
        val file = fixture.configureByText("b.by", "x = 1\n")
        val document = fixture.editor.document
        fixture.project.service<ByDocstringSpanCache>()
            .remember(file.virtualFile, document.modificationStamp, emptyList())

        assertEquals(emptyList<ByDocstring>(), ByDocstringSpans.recorded(file))
        assertFalse(refresher.isStale(file.virtualFile))
    }

    @Test
    fun `an answer from before the file changed is not an answer`() {
        val file = fixture.configureByText("c.by", "x = 1\n")
        fixture.project.service<ByDocstringSpanCache>()
            .remember(file.virtualFile, fixture.editor.document.modificationStamp, emptyList())
        fixture.type("y = 2\n")

        assertTrue(refresher.isStale(file.virtualFile))
    }

    @Test
    fun `a server becoming ready drops what the previous one said`() {
        val file = fixture.configureByText("d.by", "x = 1\n")
        refresher
        fixture.project.service<ByDocstringSpanCache>()
            .remember(file.virtualFile, fixture.editor.document.modificationStamp, emptyList())

        fixture.project.messageBus.syncPublisher(ByLspLifecycleListener.TOPIC).serverInitialized("buff")
        assertFalse(refresher.isStale(file.virtualFile), "buff says nothing about docstrings")

        fixture.project.messageBus.syncPublisher(ByLspLifecycleListener.TOPIC).serverInitialized("by")
        assertNull(ByDocstringSpans.recorded(file))
        assertTrue(refresher.isStale(file.virtualFile))
    }
}
