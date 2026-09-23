package dev.basedpython.pycharm.debug.hotswap

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.hotswap.SourceFileChangesListener
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What the platform's hot reload is told as documents are edited.
 *
 * The defect these exist for: the collector spoke only when its set went from empty to not and
 * back, but the platform also changes its status on its own — a reload that fails puts it back to
 * "no changes" and keeps the set — so after one failed reload nothing edited afterwards brought the
 * toolbar or the reload action back for the rest of the session.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByChangesCollectorTest {

    private val fixture by codeInsightFixture()

    private val heard = mutableListOf<String>()

    private val listener = object : SourceFileChangesListener {
        override fun onNewChanges() {
            heard += "new"
        }

        override fun onIncompatibleChanges(reason: String) {
            heard += "incompatible"
        }

        override fun onChangesCanceled() {
            heard += "canceled"
        }
    }

    private fun collecting(block: (ByChangesCollector) -> Unit) {
        val collector = ByChangesCollector(listener) { true }
        try {
            block(collector)
        } finally {
            Disposer.dispose(collector)
        }
    }

    private fun write(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(fixture.project) { document.setText(text) }
    }

    @Test
    fun `every edit says there are changes, not only the first`() = collecting { collector ->
        val ticker = fixture.configureByText("ticker.by", "print(\"before\")\n").virtualFile

        write(ticker, "print(\"after\")\n")
        // a reload that failed: the platform goes back to "no changes" and the set is kept
        write(ticker, "print(\"again\")\n")

        assertEquals(listOf("new", "new"), heard)
        assertEquals(setOf(ticker), collector.getChanges())
    }

    @Test
    fun `an edit to a second file while the first is still changed says so`() = collecting { collector ->
        val ticker = fixture.addFileToProject("ticker.by", "print(\"before\")\n").virtualFile
        val tock = fixture.addFileToProject("tock.by", "print(\"before\")\n").virtualFile

        write(ticker, "print(\"after\")\n")
        write(tock, "print(\"after\")\n")

        assertEquals(listOf("new", "new"), heard)
        assertEquals(setOf(ticker, tock), collector.getChanges())
    }

    @Test
    fun `typing an edit back out puts the changes away, and only then`() = collecting { collector ->
        val ticker = fixture.addFileToProject("ticker.by", "print(\"before\")\n").virtualFile
        val tock = fixture.addFileToProject("tock.by", "print(\"before\")\n").virtualFile

        write(ticker, "print(\"after\")\n")
        write(tock, "print(\"after\")\n")
        write(ticker, "print(\"before\")\n")
        write(tock, "print(\"before\")\n")

        // the third is still a change, of `tock`; the fourth leaves nothing
        assertEquals(listOf("new", "new", "new", "canceled"), heard)
        assertEquals(emptySet<VirtualFile>(), collector.getChanges())
    }
}
