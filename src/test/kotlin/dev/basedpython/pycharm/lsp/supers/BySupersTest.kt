package dev.basedpython.pycharm.lsp.supers

import com.intellij.lang.CodeInsightActions
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.BySuperMember
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TypeHierarchyItem
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import java.util.concurrent.CompletableFuture
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
    fun `the handler is registered for basedpython, so Ctrl+U is no longer dead there`() = onEdt {
        assertInstanceOf(ByGotoSuperHandler::class.java, CodeInsightActions.GOTO_SUPER.forLanguage(BasedPythonLanguage))
    }

    @Test
    fun `on the class header, the class`() = onEdt {
        val subject = BySupers.subjectAt(outline, Position(0, 7))
        assertEquals("Shape", (subject as BySuperSubject.Class).symbol.name)
    }

    @Test
    fun `in a class body between its members, the class`() = onEdt {
        val subject = BySupers.subjectAt(outline, Position(2, 0))
        assertEquals("Shape", (subject as BySuperSubject.Class).symbol.name)
    }

    @Test
    fun `on an attribute, the attribute as a member of its class`() = onEdt {
        val subject = BySupers.subjectAt(outline, Position(1, 6)) as BySuperSubject.Member
        assertEquals("sides", subject.symbol.name)
        assertEquals("Shape", subject.owner.name)
    }

    @Test
    fun `anywhere in a method, the method, even inside a function nested in it`() = onEdt {
        for (caret in listOf(Position(3, 8), Position(7, 10), Position(4, 12))) {
            val subject = BySupers.subjectAt(outline, caret) as BySuperSubject.Member
            assertEquals("area", subject.symbol.name, "at $caret")
        }
    }

    @Test
    fun `in a class nested in a method, that class`() = onEdt {
        val subject = BySupers.subjectAt(outline, Position(6, 16))
        assertEquals("Local", (subject as BySuperSubject.Class).symbol.name)
    }

    @Test
    fun `in a function no class holds, or outside every definition, nothing`() = onEdt {
        assertNull(BySupers.subjectAt(outline, Position(10, 4)))
        assertNull(BySupers.subjectAt(outline, Position(8, 0)))
    }

    @Test
    fun `the flat outline carries no nesting to find a member's class in, so nothing`() = onEdt {
        val flat = listOf(
            Either.forLeft<SymbolInformation, DocumentSymbol>(
                SymbolInformation("Shape", SymbolKind.Class, Location("file:///shapes.by", range(0, 6))),
            ),
        )
        assertNull(BySupers.subjectAt(flat, Position(0, 7)))
    }

    @Test
    fun `a base class is gone to at its name, and shown with its module`() = onEdt {
        val item = TypeHierarchyItem("Polygon", SymbolKind.Class, "file:///shapes.by", range(10, 14), Range(Position(10, 6), Position(10, 13)))
        item.detail = "shapes"

        assertEquals(
            BySuperTarget("Polygon", "shapes", Location("file:///shapes.by", Range(Position(10, 6), Position(10, 13)))),
            BySupers.classTarget(item),
        )
    }

    /** Go to Super on `area`, a method of `Shape`, answered by a `by` that says [answer]. */
    private fun overridden(answer: (String) -> CompletableFuture<*>): Pair<BySuperAnswer, List<String>> {
        val asked = mutableListOf<String>()
        val client = AnsweringClient(fixture.project) { method -> asked += method; answer(method) }
        val shape = (outline[0].right)
        val subject = BySuperSubject.Member(shape.children[1], shape)
        val result = runBlocking { BySupers.overridden(client, TextDocumentIdentifier("file:///shapes.by"), "cbf29ce484222325", subject) }
        return result to asked
    }

    private val loud = BySuperMember("area", "Loud", "file:///bases.by", range(5, 6), Range(Position(5, 8), Position(5, 12)))
    private val quiet = BySuperMember("area", "Quiet", "file:///bases.by", range(9, 10), Range(Position(9, 8), Position(9, 12)))

    @Test
    fun `a member goes to what by says it overrides, in the order by gives`() = onEdt {
        val (answer, asked) = overridden { answered(listOf(loud, quiet)) }

        assertEquals(listOf("by/superMembers"), asked)
        answer as BySuperAnswer.Targets
        assertEquals(BasedPythonBundle.message("goto.super.member.chooser", "Shape.area"), answer.chooserTitle)
        assertEquals(
            listOf(
                BySuperTarget("Loud.area", "bases.by", Location("file:///bases.by", Range(Position(5, 8), Position(5, 12)))),
                BySuperTarget("Quiet.area", "bases.by", Location("file:///bases.by", Range(Position(9, 8), Position(9, 12)))),
            ),
            answer.targets,
        )
    }

    @Test
    fun `a member that overrides nothing is said to`() = onEdt {
        val (answer, _) = overridden { answered(emptyList<BySuperMember>()) }
        assertEquals(BySuperAnswer.Nowhere(BasedPythonBundle.message("goto.super.member.none", "Shape.area")), answer)
    }

    @Test
    fun `no member where the outline has one is by's answer, and said as that`() = onEdt {
        val (answer, _) = overridden { answered(null) }
        assertEquals(BySuperAnswer.Nowhere(BasedPythonBundle.message("goto.super.member.unknown", "Shape.area")), answer)
    }

    @Test
    fun `a by without the request is said to be one, and nothing else is asked instead`() = onEdt {
        val (answer, asked) = overridden { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/superMembers") }

        assertEquals(BySuperAnswer.Nowhere(BasedPythonBundle.message("goto.super.member.unsupported", "Shape.area")), answer)
        assertEquals(listOf("by/superMembers"), asked)
    }

    @Test
    fun `any other error is a by that did not answer`() = onEdt {
        val (answer, _) = overridden { failed(ResponseErrorCode.InternalError) }
        assertEquals(BySuperAnswer.Nowhere(BasedPythonBundle.message("goto.super.noAnswer", "by/superMembers")), answer)
    }

    @Test
    fun `a member the superclass synthesizes says so beside its file`() = onEdt {
        val init = BySuperMember("__init__", "Point", "file:///p%20q/points.by", range(3, 5), Range(Position(3, 6), Position(3, 11)), synthesized = true)
        assertEquals(
            BySuperTarget(
                "Point.__init__",
                BasedPythonBundle.message("goto.super.member.synthesized", "points.by"),
                Location("file:///p%20q/points.by", Range(Position(3, 6), Position(3, 11))),
            ),
            BySupers.memberTarget(init),
        )
    }

    private fun symbol(name: String, kind: SymbolKind, from: Int, to: Int, vararg children: DocumentSymbol) =
        DocumentSymbol(name, kind, range(from, to), Range(Position(from, 4), Position(from, 4 + name.length)), null, children.toList())

    private fun range(from: Int, to: Int) = Range(Position(from, 0), Position(to, 40))
}
