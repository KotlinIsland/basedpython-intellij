package dev.basedpython.pycharm.lsp.vision

import com.intellij.codeInsight.codeVision.settings.PlatformCodeVisionIds
import com.intellij.codeInsight.codeVision.ui.model.TextCodeVisionEntry
import com.intellij.codeInsight.hints.codeVision.DaemonBoundCodeVisionProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByDeclarationUsages
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

/**
 * The counts above each declaration decide nothing about the source: what is a declaration, and how
 * many places use, subclass or override it, is `by`'s. What is left here is the platform's side —
 * which switches turn them off, what each count reads as, and which part of the text it spans — and
 * asking `by` the one request.
 */
@TestFixtures
class ByCountsCodeVisionTest {

    private val fixture by codeInsightFixture()

    private var index = 0

    /**
     * ```
     * 0 class Shape:
     * 1     @abstractmethod
     * 2     def area(self): ...
     * 3
     * 4 def total(): ...
     * ```
     */
    private val source = "class Shape:\n    @abstractmethod\n    def area(self): ...\n\ndef total(): ...\n"

    private fun file(): PsiFile = fixture.addFileToProject("pkg/shapes${index++}.by", source)

    private fun range(startLine: Int, startCharacter: Int, endLine: Int, endCharacter: Int) =
        Range(Position(startLine, startCharacter), Position(endLine, endCharacter))

    private fun shape(references: Int? = null, implementations: Int? = null) =
        ByDeclarationUsages("Shape", "class", range(0, 0, 2, 23), range(0, 6, 0, 11), references, implementations)

    private fun area(references: Int? = null, implementations: Int? = null, abstract: Boolean = false) =
        ByDeclarationUsages("area", "method", range(1, 4, 2, 23), range(2, 8, 2, 12), references, implementations, abstract)

    private fun total(references: Int? = null) =
        ByDeclarationUsages("total", "function", range(4, 0, 4, 16), range(4, 4, 4, 9), references)

    /** What [provider] draws for [declarations] over a fresh file: each entry's text and the text it spans. */
    private fun drawn(provider: ByCountsCodeVisionProvider, declarations: List<ByDeclarationUsages>): List<Pair<String, String>> {
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        return provider.entries(document, declarations).map { (range, entry) ->
            (entry as TextCodeVisionEntry).text to document.getText(range)
        }
    }

    @Test
    fun `the counts are switched by the Usages and Inheritors settings every language shares`() = onEdt {
        val ours = ExtensionPointName.create<DaemonBoundCodeVisionProvider>("com.intellij.codeInsight.daemonBoundCodeVisionProvider")
            .extensionList
            .filterIsInstance<ByCountsCodeVisionProvider>()
            .associate { it.id to it.groupId }
        assertEquals(
            mapOf(
                ByUsagesCodeVisionProvider.ID to PlatformCodeVisionIds.USAGES.key,
                ByInheritorsCodeVisionProvider.ID to PlatformCodeVisionIds.INHERITORS.key,
            ),
            ours,
        )
    }

    @Test
    fun `usages are counted above every declaration, none included, from its first decorator`() = onEdt {
        val drawn = drawn(ByUsagesCodeVisionProvider(), listOf(shape(references = 1), area(references = 0), total(references = 3)))
        assertEquals(
            listOf(
                "1 usage" to source.substring(0, source.indexOf("\n\ndef")),
                "no usages" to "@abstractmethod\n    def area(self): ...",
                "3 usages" to "def total(): ...",
            ),
            drawn,
        )
    }

    @Test
    fun `a class has inheritors, an abstract method implementations, any other method overrides`() = onEdt {
        val drawn = drawn(
            ByInheritorsCodeVisionProvider(),
            listOf(shape(implementations = 2), area(implementations = 1, abstract = true)),
        ).map { it.first }
        assertEquals(listOf("2 inheritors", "1 implementation"), drawn)

        val overridden = drawn(ByInheritorsCodeVisionProvider(), listOf(area(implementations = 3))).map { it.first }
        assertEquals(listOf("3 overrides"), overridden)
    }

    @Test
    fun `nothing is drawn for what has no inheritors, or was not counted`() = onEdt {
        assertEquals(
            emptyList<Pair<String, String>>(),
            drawn(ByInheritorsCodeVisionProvider(), listOf(shape(implementations = 0), total())),
        )
        assertEquals(emptyList<Pair<String, String>>(), drawn(ByUsagesCodeVisionProvider(), listOf(shape(implementations = 2))))
    }

    /**
     * A click asks `by` from where the declaration's name is when it is clicked, which is not where
     * it was when the pass ran if the text has been edited since.
     */
    @Test
    fun `an entry follows its declaration's name through an edit`() = onEdt {
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val (_, entry) = ByUsagesCodeVisionProvider().entries(document, listOf(area(references = 1))).single()

        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "import abc\n") }

        val marker = entry.getUserData(ByCountsCodeVisionProvider.NAME_MARKER)!!
        assertEquals("area", document.getText(TextRange(marker.startOffset, marker.endOffset)))
    }

    private fun ask(count: ByUsageCounts.Count, answer: (String) -> CompletableFuture<*>): Pair<ByUsageCounts.Asked, List<String>> {
        val asked = mutableListOf<String>()
        val client = AnsweringClient(fixture.project) { method -> asked += method; answer(method) }
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        return runBlocking { ByUsageCounts.ask(client, file.virtualFile, document, count) } to asked
    }

    @Test
    fun `one request counts every declaration of the document`() = onEdt {
        val declarations = listOf(shape(references = 1), total(references = 0))
        val (answer, asked) = ask(ByUsageCounts.Count.REFERENCES) { answered(declarations) }
        assertEquals(ByUsageCounts.Asked.Answered(declarations), answer)
        assertEquals(listOf("by/documentUsageCounts"), asked)
    }

    @Test
    fun `a by that declines is a document with nothing to draw`() = onEdt {
        val (answer, _) = ask(ByUsageCounts.Count.IMPLEMENTATIONS) { answered<Any>(null) }
        assertEquals(ByUsageCounts.Asked.Answered(emptyList()), answer)
    }

    @Test
    fun `a by without the request is told apart from one that failed`() = onEdt {
        val (unknown, _) = ask(ByUsageCounts.Count.REFERENCES) {
            failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/documentUsageCounts")
        }
        assertEquals(ByUsageCounts.Asked.Unknown, unknown)

        val (failed, _) = ask(ByUsageCounts.Count.REFERENCES) { failed(ResponseErrorCode.InternalError) }
        assertEquals(ByUsageCounts.Asked.Failed, failed)
    }
}
