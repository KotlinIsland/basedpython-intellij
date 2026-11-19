package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.impl.LineMarkersPass
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.ByCounterpartDeclaration
import dev.basedpython.pycharm.lsp.ext.ByCounterpartModule
import dev.basedpython.pycharm.lsp.ext.ByCounterpartTarget
import dev.basedpython.pycharm.lsp.ext.ByDocumentCounterparts
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The gutter's stub icons, drawn from `by/documentCounterparts` — one answer for the document, an
 * icon on each declaration's name that has a counterpart.
 */
@TestFixtures
class ByCounterpartMarkersTest {

    private val fixture by codeInsightFixture()

    /**
     * ```
     * 0 class A(B):
     * 1     def f(self, a): ...
     * 2     def h(self): ...
     * ```
     */
    private val module = "class A(B):\n    def f(self, a): ...\n    def h(self): ...\n"

    /**
     * ```
     * 0 class A(B):
     * 1     def f(self, a: int) -> None: ...
     * 2     def g(self, a: int) -> None: ...
     * ```
     */
    private val stub = "class A(B):\n    def f(self, a: int) -> None: ...\n    def g(self, a: int) -> None: ...\n"

    private fun range(line: Int, start: Int, length: Int) = Range(Position(line, start), Position(line, start + length))

    private fun target(uri: String, line: Int, start: Int, length: Int) =
        ByCounterpartTarget(uri, range(line, 0, 40), range(line, start, length))

    private fun declaration(name: String, line: Int, start: Int, length: Int, vararg targets: ByCounterpartTarget) =
        ByCounterpartDeclaration(name, if ('.' in name) "function" else "class", range(line, start, length), targets = targets.toList())

    /** `mod.py` has `mod.pyi`: `A` and `A.f` are in both, `A.h` only here. */
    private val implementation = ByDocumentCounterparts(
        role = ByDocumentCounterparts.IMPLEMENTATION,
        counterparts = listOf(ByCounterpartModule("file:///p/mod.pyi")),
        declarations = listOf(
            declaration("A", 0, 6, 1, target("file:///p/mod.pyi", 0, 6, 1)),
            declaration("A.f", 1, 8, 1, target("file:///p/mod.pyi", 1, 8, 1)),
        ),
    )

    private fun markers(name: String, text: String, counterparts: ByDocumentCounterparts): List<LineMarkerInfo<PsiElement>> {
        val file = fixture.configureByText(name, text)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val elements = PsiTreeUtil.collectElements(file) { true }.toHashSet<PsiElement>()
        return ByCounterpartMarkers.markers(file, document, counterparts, elements)
    }

    private fun described(markers: List<LineMarkerInfo<*>>, text: String) =
        markers.map { Triple(text.substring(it.startOffset, it.endOffset), it.icon, it.lineMarkerTooltip) }

    @Test
    fun `each declaration the stub has too gets its icon on its name, worded as PyCharm's`() = onEdt {
        assertEquals(
            listOf(
                Triple("A", AllIcons.Gutter.Unique, "Has stub in mod.pyi"),
                Triple("f", AllIcons.Gutter.Unique, "Has stub in mod.pyi"),
            ),
            described(markers("mod.by", module, implementation), module),
        )
    }

    @Test
    fun `a stub's declarations say what they are the stub for, and where`() = onEdt {
        val answer = ByDocumentCounterparts(
            role = ByDocumentCounterparts.STUB,
            counterparts = listOf(ByCounterpartModule("file:///p/mod.py")),
            declarations = listOf(declaration("A.f", 1, 8, 1, target("file:///p/mod.py", 1, 8, 1))),
        )
        assertEquals(
            listOf(Triple("f", AllIcons.Gutter.Unique, "Stub for A.f in mod.py")),
            described(markers("mod.byi", stub, answer), stub),
        )
    }

    /** Overloads, or the branches of a `sys.version_info` check: one icon, every place in it. */
    @Test
    fun `several counterparts are one icon that says how many, and where`() = onEdt {
        val overloads = ByDocumentCounterparts(
            role = ByDocumentCounterparts.IMPLEMENTATION,
            counterparts = listOf(ByCounterpartModule("file:///p/mod.pyi")),
            declarations = listOf(
                declaration("A.f", 1, 8, 1, target("file:///p/mod.pyi", 3, 8, 1), target("file:///p/mod.pyi", 5, 8, 1)),
            ),
        )
        assertEquals(listOf("Has 2 stubs in mod.pyi"), markers("mod.by", module, overloads).map { it.lineMarkerTooltip })

        val branches = ByDocumentCounterparts(
            role = ByDocumentCounterparts.STUB,
            counterparts = listOf(ByCounterpartModule("file:///p/mod.py")),
            declarations = listOf(
                declaration("A.f", 1, 8, 1, target("file:///p/mod.py", 1, 8, 1), target("file:///p/my%20mod.py", 4, 8, 1)),
            ),
        )
        assertEquals(
            listOf("Stub for A.f, 2 definitions in mod.py, my mod.py"),
            markers("mod.byi", stub, branches).map { it.lineMarkerTooltip },
        )
    }

    @Test
    fun `a declaration with nowhere to go, or a name outside the elements asked about, gets nothing`() = onEdt {
        val answer = implementation.copy(
            declarations = listOf(
                ByCounterpartDeclaration("A", "class", range(0, 6, 1), targets = emptyList()),
                ByCounterpartDeclaration("A.f", "function", range(1, 8, 1), targets = null),
            ),
        )
        assertTrue(markers("mod.by", module, answer).isEmpty())

        val file = fixture.configureByText("mod.by", module)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        // the pass asks about the elements of a range; one with neither name in it draws neither icon
        val firstLine = PsiTreeUtil.collectElements(file) { it.textRange.endOffset <= 5 }.toHashSet<PsiElement>()
        assertTrue(ByCounterpartMarkers.markers(file, document, implementation, firstLine).isEmpty())
    }

    /** A name, not the token it starts in: a TextMate `.pyi` is one leaf from end to end. */
    @Test
    fun `the icon covers the name, in a file with no tokens of its own`() = onEdt {
        val answer = ByDocumentCounterparts(
            role = ByDocumentCounterparts.STUB,
            counterparts = listOf(ByCounterpartModule("file:///p/mod.py")),
            declarations = listOf(declaration("A.g", 2, 8, 1, target("file:///p/mod.py", 2, 8, 1))),
        )
        val drawn = markers("mod.txt", stub, answer).single()
        assertEquals("g", stub.substring(drawn.startOffset, drawn.endOffset))
    }

    /** What the pass draws, end to end: registered for every language, and asking about the document once. */
    @Test
    fun `the pass draws the icons in a basedpython file and in a stub that is no language of ours`() = onEdt {
        assertTrue(LineMarkersPass.getMarkerProviders(BasedPythonLanguage, fixture.project).any { it is ByCounterpartMarkers })
        assertTrue(LineMarkersPass.getMarkerProviders(PlainTextLanguage.INSTANCE, fixture.project).any { it is ByCounterpartMarkers })

        assertEquals(listOf("Has stub in mod.pyi", "Has stub in mod.pyi"), slowMarkers(configure("mod.by", module, implementation)))

        val stubAnswer = ByDocumentCounterparts(
            role = ByDocumentCounterparts.STUB,
            counterparts = listOf(ByCounterpartModule("file:///p/mod.py")),
            declarations = listOf(declaration("A", 0, 6, 1, target("file:///p/mod.py", 0, 6, 1))),
        )
        // a `.pyi` in an IDE without the Python plugin, here plain text
        assertEquals(listOf("Stub for A in mod.py"), slowMarkers(configure("mod.pyi", stub, stubAnswer)))
    }

    @Test
    fun `a file by is not given is not asked about`() = onEdt {
        assertTrue(slowMarkers(configure("notes.txt", module, implementation)).isEmpty())
    }

    private fun configure(name: String, text: String, answer: ByDocumentCounterparts): PsiFile {
        val file = fixture.configureByText(name, text)
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        ByCounterparts.getInstance(fixture.project).remember(document, file.virtualFile, answer)
        return file
    }

    private fun slowMarkers(file: PsiFile): List<String?> = offEdt {
        val result = mutableListOf<LineMarkerInfo<*>>()
        ByCounterpartMarkers().collectSlowLineMarkers(PsiTreeUtil.collectElements(file) { true }.toList(), result)
        result.map { it.lineMarkerTooltip }
    }
}
