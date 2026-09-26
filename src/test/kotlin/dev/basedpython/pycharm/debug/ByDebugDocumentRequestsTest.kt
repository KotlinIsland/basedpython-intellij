package dev.basedpython.pycharm.debug

import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.debug.dfa.askDataFlowAt
import dev.basedpython.pycharm.debug.hotswap.ByRestage
import dev.basedpython.pycharm.testFramework.RecordingByClient
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * The debugger's requests to `by` about a file tell `by` about the file first.
 *
 * A re-stage and a data-flow question are both about files the program is running, and nothing
 * makes those content: a `.by` under an excluded directory, or one outside the project, is a file
 * the platform's client never opens. Every other document request goes through
 * `ByServerDocuments.ensureOpen` for that reason; these did not, so `by` refused the data-flow
 * question outright and transpiled a re-stage without the IDE's text for the file.
 */
@TestFixtures
class ByDebugDocumentRequestsTest {

    private val fixture by codeInsightFixture()

    private val temp: Path = Files.createTempDirectory("debug-document-requests-test")

    @AfterEach
    fun cleanUp() = onEdt {
        temp.toFile().deleteRecursively()
    }

    /** A file on disk outside every content root. */
    private fun outside(name: String, text: String): VirtualFile {
        val path = Files.createDirectories(temp.resolve("outside")).resolve(name)
        Files.writeString(path, text)
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)!!
    }

    @Test
    fun `a re-stage opens every file of the edit before it asks`() = onEdt {
        val client = RecordingByClient(fixture.project)
        val first = outside("first.by", "x = 1\n")
        val second = outside("second.by", "y = 2\n")

        ByRestage.ask(fixture.project, client, listOf(first, second), temp.resolve("build").toString())

        assertEquals(
            listOf(
                "open first.by v1 x = 1",
                "open second.by v1 y = 2",
                "watched first.by changed",
                "watched second.by changed",
                "request by/transpileForBuild",
            ),
            client.sent,
        )
    }

    @Test
    fun `a data-flow question opens the file before it asks`() = onEdt {
        val client = RecordingByClient(fixture.project)
        val file = outside("stopped.by", "x = 1\n")

        runBlocking(Dispatchers.Default) { askDataFlowAt(fixture.project, client, file, 1, emptyList()) }

        assertEquals(listOf("open stopped.by v1 x = 1", "request by/dataFlowAt"), client.sent)
    }

    @Test
    fun `a file under a content root is not opened, since the platform opens it`() = onEdt {
        val client = RecordingByClient(fixture.project)
        val file = fixture.configureByText("content.by", "x = 1\n").virtualFile

        ByRestage.ask(fixture.project, client, listOf(file), temp.resolve("build").toString())
        runBlocking(Dispatchers.Default) { askDataFlowAt(fixture.project, client, file, 1, emptyList()) }

        // the re-stage still says the file changed on disk, which is not an open — see ByRestageTest
        assertEquals(
            listOf("watched content.by changed", "request by/transpileForBuild", "request by/dataFlowAt"),
            client.sent,
        )
    }
}
