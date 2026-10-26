package dev.basedpython.pycharm.editor

import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.ui.breadcrumbs.BreadcrumbsProvider
import com.intellij.psi.PsiElement
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `.by` breadcrumbs are the classes, functions and control clauses `by`'s outline says the caret is
 * in, and sticky lines its classes, functions and `with`s — not every document symbol, as the
 * platform's LSP breadcrumbs had them, constants and fields included.
 */
@TestFixtures
class ByBreadcrumbsProviderTest {

    private val fixture by codeInsightFixture()

    private val source = """
        |class Shape:
        |    name: str
        |
        |    def area(self, xs: list[int]) -> int:
        |        for x in xs:
        |            if x > 0:
        |                pass
        |            else:
        |                with open("p") as fh:
        |                    pass
        |        return 0
        |
        |
        |LONG = [
        |    1,
        |]
        |""".trimMargin()

    private fun configure() {
        fixture.configureByText("a.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            compound(name = "Shape") {
                clause("class Shape:") {
                    simple("name: str")
                    def("def area(self, xs: list[int]) -> int:") {
                        compound {
                            clause("for x in xs:") {
                                compound {
                                    clause("if x > 0:") { simple("pass") }
                                    clause("else:") {
                                        compound { clause("with open(\"p\") as fh:") { simple("pass") } }
                                    }
                                }
                            }
                        }
                        simple("return 0")
                    }
                }
            }
            simple("LONG = [\n    1,\n]")
        }
    }

    /** What the platform's collector does: up from the token at [offset] through the provider. */
    private fun chain(offset: Int, accept: (BreadcrumbsProvider, PsiElement) -> Boolean): List<String> {
        val provider = ByBreadcrumbsProvider()
        val crumbs = mutableListOf<String>()
        var element: PsiElement? = fixture.file.findElementAt(offset)
        while (element != null) {
            if (accept(provider, element)) crumbs.add(0, provider.getElementInfo(element))
            element = provider.getParent(element)
        }
        return crumbs
    }

    @Test
    fun `crumbs are classes, functions and control clauses, as in py`() = onEdt {
        configure()
        val crumbs = { at: String -> chain(source.indexOf(at)) { p, e -> p.acceptElement(e) } }
        assertEquals(listOf("Shape", "area()", "for x in xs", "if x > 0"), crumbs("pass"))
        assertEquals(listOf("Shape", "area()", "for x in xs", "else", "with open(\"p\") as fh"), crumbs("pass\n        return"))
        assertEquals(listOf("Shape", "area()"), crumbs("return 0"))
        assertEquals(emptyList<String>(), crumbs("1,"), "a constant is no crumb")
    }

    @Test
    fun `sticky lines are classes, functions and withs only`() = onEdt {
        configure()
        assertEquals(listOf("Shape", "area()", "with open(\"p\") as fh"), chain(source.indexOf("pass\n        return")) { p, e -> p.acceptStickyElement(e) })
        assertEquals(emptyList<String>(), chain(source.indexOf("1,")) { p, e -> p.acceptStickyElement(e) })
    }

    @Test
    fun `two tokens of one block reach the same element`() = onEdt {
        configure()
        val provider = ByBreadcrumbsProvider()
        assertSame(
            provider.getParent(fixture.file.findElementAt(source.indexOf("return 0"))!!),
            provider.getParent(fixture.file.findElementAt(source.indexOf("0\n"))!!),
        )
    }

    @Test
    fun `basedpython is on the breadcrumbs settings page`() {
        assertTrue(BreadcrumbsProvider.EP_NAME.extensionList.any { it is ByBreadcrumbsProvider })
    }
}
