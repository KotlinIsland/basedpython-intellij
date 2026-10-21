package dev.basedpython.pycharm.lsp.imports

import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.codeInsight.editorActions.CopyPastePostProcessor
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ByTextHash
import dev.basedpython.pycharm.lsp.ext.ByCopiedImport
import dev.basedpython.pycharm.lsp.ext.ByCopiedImportsParams
import dev.basedpython.pycharm.lsp.ext.ByCopiedText
import dev.basedpython.pycharm.lsp.ext.ByPastedImports
import dev.basedpython.pycharm.lsp.ext.ByPastedImportsParams
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import com.intellij.openapi.command.WriteCommandAction
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.datatransfer.StringSelection

/**
 * What pasted code imports is `by`'s answer twice over: the imports the copied code needs, and which
 * of them the place it lands is missing. What is left here is carrying the first answer from the copy
 * to the paste without handing the platform's clipboard anything of this plugin's, honouring *Insert
 * imports on paste*, and applying the second answer only to the text it is about.
 */
@TestFixtures
class ByPasteImportsTest {

    private val fixture by codeInsightFixture()

    private var pasteSetting = CodeInsightSettings.YES

    @BeforeEach
    fun keep() {
        pasteSetting = CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE
    }

    @AfterEach
    fun restore() {
        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = pasteSetting
    }

    private val math = ByCopiedImport("math", "math")
    private val scale = ByCopiedImport("SCALE", "shapes", "SCALE")

    private fun insertAt0(text: String) = TextEdit(Range(Position(0, 0), Position(0, 0)), text)

    @Test
    fun `the paste processor is registered`() {
        assertTrue(CopyPastePostProcessor.EP_NAME.extensionList.any { it is ByPasteImports })
    }

    /** A copy of [text] at the start of a `.by` file holding it. */
    private fun copyOf(text: String, name: String = "copied${index++}.by"): ByCopy {
        val document = fixture.addFileToProject(name, text).viewProvider.document
        val file = fixture.findFileInTempDir(name)
        return ByCopy(file, document, listOf(ByCopy.Piece(text, document.createRangeMarker(0, 0))))
    }

    private var index = 0

    /**
     * The copy is kept against the clipboard content it made, which is the platform's own object —
     * nothing of this plugin's goes into it — and only if that content holds the text copied: a copy
     * that never reached the clipboard is not given to whatever does.
     */
    @Test
    fun `a copy goes with the clipboard content that holds its text`() = onEdt {
        val copies = ByCopies.getInstance()
        val first = copyOf("x = math.pi")
        copies.copied("x = math.pi", first)
        val content = StringSelection("x = math.pi")
        CopyPasteManager.getInstance().setContents(content)
        assertSame(first, copies.copyOf(content))

        copies.copied("y = SCALE", copyOf("y = SCALE"))
        val other = StringSelection("something else")
        CopyPasteManager.getInstance().setContents(other)
        assertNull(copies.copyOf(other))
        val later = StringSelection("y = SCALE")
        CopyPasteManager.getInstance().setContents(later)
        assertNull(copies.copyOf(later), "a pending copy is given to the next content or to none")
    }

    /**
     * A paste is handed the system clipboard's view of the content — on macOS a proxy around it —
     * rather than the content itself, and that is matched to the newest content the IDE put on the
     * clipboard by its text.
     */
    @Test
    fun `a proxy of the newest clipboard content is matched by its text`() = onEdt {
        val copies = ByCopies.getInstance()
        val copy = copyOf("x = math.pi")
        copies.copied("x = math.pi", copy)
        CopyPasteManager.getInstance().setContents(StringSelection("x = math.pi"))

        assertSame(copy, copies.copyOf(StringSelection("x = math.pi")))
        assertNull(copies.copyOf(StringSelection("x = math.tau")))

        CopyPasteManager.getInstance().setContents(StringSelection("something newer"))
        assertNull(copies.copyOf(StringSelection("x = math.pi")), "only the newest content is matched by text")
    }

    @Test
    fun `Never takes nothing from the clipboard`() = onEdt {
        val copy = copyOf("x = math.pi")
        ByCopies.getInstance().copied("x = math.pi", copy)
        val content = StringSelection("x = math.pi")
        CopyPasteManager.getInstance().setContents(content)
        val processor = ByPasteImports()

        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = CodeInsightSettings.NO
        assertTrue(processor.extractTransferableData(content).isEmpty())

        CodeInsightSettings.getInstance().ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
        assertSame(copy, processor.extractTransferableData(content).single().copy)
    }

    /**
     * Asked at the paste, about the copied text and where it now is in the source: a cut since the
     * copy has taken the text out, and the marker still says where it was.
     */
    @Test
    fun `the copied text is asked about at the place it was cut from`() = onEdt {
        val psiFile = fixture.configureByText("cut.by", "import math\nx = 1\ny = math.pi\n")
        val document = psiFile.viewProvider.document
        val start = document.text.indexOf("y = ")
        val copy = ByCopy(psiFile.virtualFile, document, listOf(ByCopy.Piece("y = math.pi", document.createRangeMarker(start, start))))
        WriteCommandAction.runWriteCommandAction(fixture.project) {
            document.deleteString(start, start + "y = math.pi".length)
            document.insertString(0, "# a line\n")
        }
        val client = AnsweringClient(fixture.project) { answered(listOf(math)) }

        assertEquals(listOf(math), ByPasteImports.copiedImports(client, copy))
        val params = client.sentParams.single() as ByCopiedImportsParams
        assertEquals(listOf(ByCopiedText("y = math.pi", Position(3, 0))), params.copies)
        assertEquals(ByTextHash.of(document.immutableCharSequence), params.textHash)

        val old = AnsweringClient(fixture.project) { failed(ResponseErrorCode.MethodNotFound) }
        assertNull(ByPasteImports.copiedImports(old, copy))
    }


    /** *Always*: the missing imports are added, with the edits `by` gave for the text it was asked about. */
    @Test
    fun `Always adds what by says the paste is missing`() = onEdt {
        val psiFile = fixture.configureByText("always.by", "x = math.pi * SCALE\n")
        val document = psiFile.viewProvider.document
        val bounds = document.createRangeMarker(0, document.textLength - 1)
        val client = AnsweringClient(fixture.project) {
            answered(ByPastedImports(listOf(math, scale), listOf(insertAt0("import math\n"), insertAt0("from shapes import SCALE\n"))))
        }

        ByPasteImports.addImports(client, psiFile.virtualFile, document, bounds, listOf(math, scale), ask = false, ModalityState.nonModal()) {
            error("Always does not ask")
        }

        assertEquals("import math\nfrom shapes import SCALE\nx = math.pi * SCALE\n", document.text)
        val params = client.sentParams.single() as ByPastedImportsParams
        assertEquals(Range(Position(0, 0), Position(0, 19)), params.range)
        assertEquals(listOf(math, scale), params.imports)
    }

    /**
     * *Ask*, and the user keeps some: the edits of some imports are not some of the edits, so `by` is
     * asked again for the ones kept, and its answer to that is what is applied.
     */
    @Test
    fun `Ask asks again for the imports chosen`() = onEdt {
        val psiFile = fixture.configureByText("ask.by", "x = math.pi * SCALE\n")
        val document = psiFile.viewProvider.document
        val bounds = document.createRangeMarker(0, document.textLength - 1)
        val answers = ArrayDeque(
            listOf(
                ByPastedImports(listOf(math, scale), listOf(insertAt0("import math\nfrom shapes import SCALE\n"))),
                ByPastedImports(listOf(scale), listOf(insertAt0("from shapes import SCALE\n"))),
            ),
        )
        val answering = AnsweringClient(fixture.project) { answered(answers.removeFirst()) }
        var offered: List<ByCopiedImport>? = null

        ByPasteImports.addImports(answering, psiFile.virtualFile, document, bounds, listOf(math, scale), ask = true, ModalityState.nonModal()) {
            offered = it
            listOf(scale)
        }

        assertEquals(listOf(math, scale), offered)
        assertEquals("from shapes import SCALE\nx = math.pi * SCALE\n", document.text)
        assertEquals(listOf(listOf(math, scale), listOf(scale)), answering.sentParams.map { (it as ByPastedImportsParams).imports })
    }

    @Test
    fun `nothing missing, or nothing chosen, changes nothing`() = onEdt {
        val psiFile = fixture.configureByText("none.by", "x = math.pi\n")
        val document = psiFile.viewProvider.document
        val bounds = document.createRangeMarker(0, document.textLength - 1)
        val nothing = AnsweringClient(fixture.project) { answered(ByPastedImports()) }
        ByPasteImports.addImports(nothing, psiFile.virtualFile, document, bounds, listOf(math), ask = true, ModalityState.nonModal()) {
            error("nothing to ask about")
        }
        val missing = AnsweringClient(fixture.project) { answered(ByPastedImports(listOf(math), listOf(insertAt0("import math\n")))) }
        ByPasteImports.addImports(missing, psiFile.virtualFile, document, bounds, listOf(math), ask = true, ModalityState.nonModal()) {
            emptyList()
        }
        assertEquals("x = math.pi\n", document.text)
    }

    @Test
    fun `an import is written as the statement that adds it`() {
        assertEquals("import math", ByPasteImportsDialog.statementOf(ByCopiedImport("math", "math")))
        assertEquals("import os.path", ByPasteImportsDialog.statementOf(ByCopiedImport("os", "os.path")))
        assertEquals("import numpy as np", ByPasteImportsDialog.statementOf(ByCopiedImport("np", "numpy")))
        assertEquals("from pathlib import Path", ByPasteImportsDialog.statementOf(ByCopiedImport("Path", "pathlib", "Path")))
        assertEquals("from pathlib import Path as P", ByPasteImportsDialog.statementOf(ByCopiedImport("P", "pathlib", "Path")))
    }
}
