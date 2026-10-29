package dev.basedpython.pycharm.editor.scope

import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which scopes of a `.by` file get an echo of their header after their last line, read off an
 * outline shaped as `by` sends it, and the echoes the editor then draws.
 */
@TestFixtures
class ByScopeEndsTest {

    private val fixture by codeInsightFixture()

    private val morgans = """
        |def coin() -> bool: return True
        |
        |class A:
        |    def f(self):
        |
        |        if coin():
        |            for x in [1, 2]:
        |                print(1)
        |                print(1)
        |            print(1)
        |        print(1)
        |""".trimMargin()

    /** Each echo as the line it follows, its column and its text. */
    private fun echoes(): List<Triple<Int, Int, String>> {
        val document = fixture.editor.document
        return ByScopeEnds.of(OutlineSpec.outline(document, morgansOutline), document.immutableCharSequence)
            .map { Triple(document.getLineNumber(it.end), it.column, it.text) }
    }

    private val morgansOutline: OutlineSpec.Suite.() -> Unit = {
        def("def coin() -> bool:") { simple("return True") }
        compound(name = "A") {
            clause("class A:") {
                def("def f(self):", listOf("self")) {
                    compound {
                        clause("if coin():") {
                            compound {
                                clause("for x in [1, 2]:") {
                                    call("print(1)")
                                    call("print(1)")
                                }
                            }
                            call("print(1)")
                        }
                    }
                    call("print(1)")
                }
            }
        }
    }

    @Test
    fun `every scope but a one-line one or a definition echoes its header after its last line`() = onEdt {
        fixture.configureByText("a.by", morgans)
        assertEquals(
            listOf(
                Triple(8, 12, "for x in [1, 2]"),
                Triple(9, 8, "if coin()"),
            ),
            echoes(),
        )
    }

    @Test
    fun `an if with elif and else is one scope named by its if, and the def around it has none`() = onEdt {
        val text = """
            |@cache
            |def g(a,
            |            b):
            |    if a:
            |        pass
            |    elif b:
            |        pass
            |    else:
            |        pass
            |""".trimMargin()
        fixture.configureByText("b.by", text)
        val document = fixture.editor.document
        val outline = OutlineSpec.outline(document) {
            compound(decorators = "@cache", name = "g") {
                clause("def g(a,\n            b):", keyword = "def") {
                    compound {
                        clause("if a:") { simple("pass") }
                        clause("elif b:") { simple("pass") }
                        clause("else:") { simple("pass") }
                    }
                }
            }
        }
        assertEquals(
            listOf("if a" to 8),
            ByScopeEnds.of(outline, document.immutableCharSequence).map { it.text to document.getLineNumber(it.end) },
        )
    }

    @Test
    fun `a decorated async def gets no echo, nor does a decorated class`() = onEdt {
        fixture.configureByText("e.by", "@cache\nasync def g():\n    pass\n@frozen\nclass C:\n    pass\n")
        val document = fixture.editor.document
        val outline = OutlineSpec.outline(document) {
            compound(decorators = "@cache", name = "g") {
                clause("async def g():", keyword = "def") { simple("pass") }
            }
            compound(decorators = "@frozen", name = "C") { clause("class C:") { simple("pass") } }
        }
        assertEquals(emptyList<String>(), ByScopeEnds.of(outline, document.immutableCharSequence).map { it.text })
    }

    @Test
    fun `async before for belongs to the header`() = onEdt {
        fixture.configureByText("c.by", "async for x in xs:\n    pass\n")
        val document = fixture.editor.document
        val outline = OutlineSpec.outline(document) {
            compound { clause("async for x in xs:", keyword = "for") { simple("pass") } }
        }
        assertEquals(listOf("async for x in xs"), ByScopeEnds.of(outline, document.immutableCharSequence).map { it.text })
    }

    @Test
    fun `the editor draws one line per echo below the scope's last line`() = onEdt {
        fixture.configureByText("d.by", morgans)
        OutlineSpec.remember(fixture.project, fixture.editor.document, morgansOutline)
        fixture.doHighlighting()
        val drawn = fixture.editor.inlayModel.getBlockElementsInRange(0, fixture.editor.document.textLength)
            .filter { !it.properties.isShownAbove }
            .map { fixture.editor.document.getLineNumber(it.offset) to it.heightInPixels / fixture.editor.lineHeight }
        assertEquals(listOf(8 to 1, 9 to 1), drawn.sortedBy { it.first })
    }
}
