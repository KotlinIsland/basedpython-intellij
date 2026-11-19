package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByCounterpartDeclaration
import dev.basedpython.pycharm.lsp.ext.ByCounterpartModule
import dev.basedpython.pycharm.lsp.ext.ByCounterpartTarget
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterparts
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Go to Related Symbol between a stub and its module, from the answer the gutter's icons are drawn from. */
@TestFixtures
class ByCounterpartsRelatedProviderTest {

    private val fixture by codeInsightFixture()

    /**
     * ```
     * 0 class A(B):
     * 1     def f(self, a):
     * 2         return a
     * ```
     */
    private val module = "class A(B):\n    def f(self, a):\n        return a\n"

    private val stub = "class A(B):\n    def f(self, a: int) -> int: ...\n"

    private fun range(startLine: Int, start: Int, endLine: Int, end: Int) = Range(Position(startLine, start), Position(endLine, end))

    private fun configure(answer: (stubUri: String) -> ByDocumentCounterparts): PsiFile {
        val stubFile = fixture.addFileToProject("mod.pyi", stub)
        val file = fixture.configureByText("mod.by", module)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        ByCounterparts.getInstance(fixture.project).remember(document, file.virtualFile, answer(stubFile.virtualFile.url))
        return file
    }

    private fun answer(stubUri: String) = ByDocumentCounterparts(
        role = ByDocumentCounterparts.IMPLEMENTATION,
        counterparts = listOf(ByCounterpartModule(stubUri)),
        declarations = listOf(
            ByCounterpartDeclaration(
                "A", "class", range(0, 6, 0, 7),
                range = range(0, 0, 2, 16),
                targets = listOf(ByCounterpartTarget(stubUri, range(0, 0, 1, 37), range(0, 6, 0, 7))),
            ),
            ByCounterpartDeclaration(
                "A.f", "function", range(1, 8, 1, 9),
                range = range(1, 4, 2, 16),
                targets = listOf(ByCounterpartTarget(stubUri, range(1, 4, 1, 37), range(1, 8, 1, 9))),
            ),
        ),
    )

    /** What Go to Related Symbol lists at [offset]: each item's name, container and group. */
    private fun items(file: PsiFile, offset: Int?): List<String> = offEdt {
        val context = if (offset == null) file else file.findElementAt(offset)!!
        ByCounterpartsRelatedProvider().getItems(context).map { item ->
            listOfNotNull(item.customName ?: (item.element as? PsiFile)?.name, item.customContainerName, item.group).joinToString(" | ")
        }
    }

    @Test
    fun `the other module is listed from the module itself`() = onEdt {
        val file = configure { answer(it) }
        assertEquals(listOf("mod.pyi | Stub"), items(file, null))
    }

    /** Each declaration's range is the whole of it, so a body has its method and its class, innermost first. */
    @Test
    fun `a body has its declarations' counterparts, innermost first, then the module`() = onEdt {
        val file = configure { answer(it) }
        assertEquals(
            listOf("A.f | mod.pyi | Stub", "A | mod.pyi | Stub", "mod.pyi | Stub"),
            items(file, module.indexOf("return")),
        )
    }

    @Test
    fun `a counterpart is gone to at its name`() = onEdt {
        val file = configure { answer(it) }
        val target = offEdt {
            ByCounterpartsRelatedProvider().getItems(file.findElementAt(module.indexOf("self"))!!).first().element!!
        }
        assertEquals(stub.indexOf("f(self"), target.textRange.startOffset)
    }

    /** A range that no longer fits the document, an answer that raced an edit, puts the caret in nothing. */
    @Test
    fun `a declaration whose range does not fit the text is in nothing`() = onEdt {
        val file = configure { uri ->
            val fresh = answer(uri)
            fresh.copy(declarations = fresh.declarations!!.map { it.copy(range = range(40, 0, 41, 0)) })
        }
        assertEquals(listOf("mod.pyi | Stub"), items(file, module.indexOf("return")))
    }

    @Test
    fun `a stub's related symbols are its implementation`() = onEdt {
        val moduleFile = fixture.addFileToProject("impl.by", module)
        val file = fixture.configureByText("impl.byi", stub)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val uri = moduleFile.virtualFile.url
        ByCounterparts.getInstance(fixture.project).remember(
            document,
            file.virtualFile,
            ByDocumentCounterparts(
                ByDocumentCounterparts.STUB,
                listOf(ByCounterpartModule(uri)),
                listOf(ByCounterpartDeclaration("A", "class", range(0, 6, 0, 7), range = range(0, 0, 1, 37), targets = listOf(ByCounterpartTarget(uri, null, range(0, 6, 0, 7))))),
            ),
        )
        assertEquals(listOf("A | impl.by | Implementation", "impl.by | Implementation"), items(file, stub.indexOf("int")))
    }
}
