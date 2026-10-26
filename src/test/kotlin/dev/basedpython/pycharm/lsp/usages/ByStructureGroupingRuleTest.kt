package dev.basedpython.pycharm.lsp.usages

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.TextRange
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.impl.FileStructureGroupRuleProvider
import dev.basedpython.pycharm.lsp.outline.OutlineSpec
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.openapi.application.readAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `.by` usage is grouped under the classes and functions `by`'s outline says it is written in,
 * outermost first, as PyCharm groups a `.py`'s by its PSI.
 */
@TestFixtures
class ByStructureGroupingRuleTest {

    private val fixture by codeInsightFixture()

    private val source = """
        |class Circle(Shape):
        |    def __init__(self, radius: float) -> None:
        |        self.radius = radius
        |
        |
        |def total(shapes: list[Shape]) -> float:
        |    return sum(s.area() for s in shapes)
        |
        |
        |x = Shape
        |""".trimMargin()

    @Test
    fun `a usage is grouped under each class and function it is in, outermost first`() = onEdt {
        val psi = fixture.configureByText("shapes.by", source)
        OutlineSpec.remember(fixture.project, fixture.editor.document) {
            compound(name = "Circle") {
                clause("class Circle(Shape):") {
                    def("def __init__(self, radius: float) -> None:") { simple("self.radius = radius") }
                }
            }
            def("def total(shapes: list[Shape]) -> float:") { simple("return sum(s.area() for s in shapes)") }
            simple("x = Shape")
        }
        val rule = ByStructureGroupingRule(fixture.project)
        suspend fun groups(at: String, delta: Int = 0): List<String> = withContext(Dispatchers.Default) {
            readAction {
                val offset = source.indexOf(at) + delta
                val usage = UsageInfo2UsageAdapter(UsageInfo(psi, offset, offset + 1))
                rule.getParentGroupsFor(usage, UsageTarget.EMPTY_ARRAY).map {
                    it.presentableGroupText + ":" + when (it.icon) {
                        AllIcons.Nodes.Class -> "class"
                        AllIcons.Nodes.Method -> "method"
                        AllIcons.Nodes.Function -> "function"
                        else -> "?"
                    }
                }
            }
        }

        assertEquals(listOf("Circle:class", "__init__:method"), groups("radius = radius", 9))
        assertEquals(listOf("Circle:class"), groups("Shape):"), "a base list is in its class")
        assertEquals(listOf("total:function"), groups("Shape]) -> float"), "an annotation is in its def")
        assertEquals(emptyList<String>(), groups("x = Shape", 4), "module level is under the file")
        assertEquals(
            withContext(Dispatchers.Default) { readAction { rule.getParentGroupsFor(UsageInfo2UsageAdapter(UsageInfo(psi, source.indexOf("self.radius"), source.indexOf("self.radius") + 4)), UsageTarget.EMPTY_ARRAY) } },
            withContext(Dispatchers.Default) { readAction { rule.getParentGroupsFor(UsageInfo2UsageAdapter(UsageInfo(psi, source.indexOf("radius\n") , source.indexOf("radius\n") + 6)), UsageTarget.EMPTY_ARRAY) } },
            "two usages in one function share its groups",
        )
    }

    @Test
    fun `the rule is offered for file structure grouping`() = onEdt {
        assertTrue(FileStructureGroupRuleProvider.EP_NAME.extensionList.any { it is ByStructureGroupRuleProvider })
    }
}
