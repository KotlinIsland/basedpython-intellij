package dev.basedpython.pycharm.debug.hotswap

import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.RecordingByClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What `by` is sent when hot reload asks it what the edited files' slots should now hold.
 *
 * The defect these exist for: `by` transpiles the text its database holds, and for a file not open
 * in an editor that is the file as it last read it from disk — which it reads again when it hears
 * the file changed. Its own file system watcher reports a save some milliseconds after the bytes
 * land, which is after a reload asked straight after the save, so that reload was answered about
 * the text before the edit: the tree's own bytes, `changed` false for every file, and "every edited
 * file already was the code the process is running" with bpd never asked. Pinned as the messages in
 * order, because the order is the fix: told, then asked.
 *
 * The other half — the pending write flushed before `by` is told — is not pinned here, because it
 * cannot fail here: the test application's file system writes a save before `saveDocument`
 * returns, and a test of the flush passed with the flush taken out. It was measured in a sandbox
 * instead; see [ByRestage.ask].
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByRestageTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `by is told an edited file changed on disk before it is asked about it`() {
        val client = RecordingByClient(fixture.project)
        val ticker = fixture.configureByText("ticker.by", "def tick(n: int) -> None:\n    print(n)\n").virtualFile

        ByRestage.ask(fixture.project, client, listOf(ticker), "/build")

        assertEquals(listOf("watched ticker.by changed", "request by/transpileForBuild"), client.sent)
    }

    @Test
    fun `every file of the set is told about, and the set is asked about once`() {
        val client = RecordingByClient(fixture.project)
        val ticker = fixture.addFileToProject("ticker.by", "def tick(n: int) -> None:\n    print(n)\n").virtualFile
        val tock = fixture.addFileToProject("tock.by", "def tock(n: int) -> None:\n    print(n)\n").virtualFile

        ByRestage.ask(fixture.project, client, listOf(ticker, tock), "/build")

        assertEquals(
            listOf("watched ticker.by changed", "watched tock.by changed", "request by/transpileForBuild"),
            client.sent,
        )
    }

}
