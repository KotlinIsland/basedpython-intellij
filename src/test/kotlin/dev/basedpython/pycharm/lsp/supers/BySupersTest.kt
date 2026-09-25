package dev.basedpython.pycharm.lsp.supers

import com.intellij.lang.CodeInsightActions
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Go to Super decides nothing about the source itself: which definition the caret is in is read off
 * `by`'s outline, and where to go comes back from `by`. What is left here is reading the outline
 * right — the innermost class or class member around the caret — and handing on what `by` said.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class BySupersTest {

    private val fixture by codeInsightFixture()

    /**
     * ```
     *  0 class Shape:
     *  1     sides = 0
     *  2
     *  3     def area(self):
     *  4         def helper():
     *  5             class Local:
     *  6                 pass
     *  7         return 0
     *  8
     *  9 def free():
     * 10     pass
     * ```
     */
    private val outline = listOf(
        symbol(
            "Shape", SymbolKind.Class, 0, 7,
            symbol("sides", SymbolKind.Field, 1, 1),
            symbol(
                "area", SymbolKind.Method, 3, 7,
                symbol("helper", SymbolKind.Function, 4, 6, symbol("Local", SymbolKind.Class, 5, 6)),
            ),
        ),
        symbol("free", SymbolKind.Function, 9, 10),
    ).map { Either.forRight<SymbolInformation, DocumentSymbol>(it) }

    @Test
    fun `the handler is registered for basedpython, so Ctrl+U is no longer dead there`() {
        assertInstanceOf(ByGotoSuperHandler::class.java, CodeInsightActions.GOTO_SUPER.forLanguage(BasedPythonLanguage))
    }

    @Test
    fun `on the class header, the class`() {
        val subject = BySupers.subjectAt(outline, Position(0, 7))
        assertEquals("Shape", (subject as BySuperSubject.Class).symbol.name)
    }

    @Test
    fun `in a class body between its members, the class`() {
        val subject = BySupers.subjectAt(outline, Position(2, 0))
        assertEquals("Shape", (subject as BySuperSubject.Class).symbol.name)
    }

    @Test
    fun `on an attribute, the attribute as a member of its class`() {
        val subject = BySupers.subjectAt(outline, Position(1, 6)) as BySuperSubject.Member
        assertEquals("sides", subject.symbol.name)
        assertEquals("Shape", subject.owner.name)
    }

    @Test
    fun `anywhere in a method, the method, even inside a function nested in it`() {
        for (caret in listOf(Position(3, 8), Position(7, 10), Position(4, 12))) {
            val subject = BySupers.subjectAt(outline, caret) as BySuperSubject.Member
            assertEquals("area", subject.symbol.name, "at $caret")
        }
    }

    @Test
    fun `in a class nested in a method, that class`() {
        val subject = BySupers.subjectAt(outline, Position(6, 16))
        assertEquals("Local", (subject as BySuperSubject.Class).symbol.name)
    }

    @Test
    fun `in a function no class holds, or outside every definition, nothing`() {
        assertNull(BySupers.subjectAt(outline, Position(10, 4)))
        assertNull(BySupers.subjectAt(outline, Position(8, 0)))
    }

    @Test
    fun `the flat outline carries no nesting to find a member's class in, so nothing`() {
        val flat = listOf(
            Either.forLeft<SymbolInformation, DocumentSymbol>(
                SymbolInformation("Shape", SymbolKind.Class, Location("file:///shapes.by", range(0, 6))),
            ),
        )
        assertNull(BySupers.subjectAt(flat, Position(0, 7)))
    }

    @Test
    fun `a base class is gone to at its name, and shown with its module`() {
        val item = TypeHierarchyItem("Polygon", SymbolKind.Class, "file:///shapes.by", range(10, 14), Range(Position(10, 6), Position(10, 13)))
        item.detail = "shapes"

        assertEquals(
            BySuperTarget("Polygon", "shapes", Location("file:///shapes.by", Range(Position(10, 6), Position(10, 13)))),
            BySupers.classTarget(item),
        )
    }

    private fun symbol(name: String, kind: SymbolKind, from: Int, to: Int, vararg children: DocumentSymbol) =
        DocumentSymbol(name, kind, range(from, to), Range(Position(from, 4), Position(from, 4 + name.length)), null, children.toList())

    private fun range(from: Int, to: Int) = Range(Position(from, 0), Position(to, 40))
}
