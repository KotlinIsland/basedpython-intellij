package dev.basedpython.pycharm.navigation

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.TreeElement
import com.intellij.navigation.ColoredItemPresentation
import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.editor.colors.CodeInsightColors
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByInheritedMember
import dev.basedpython.pycharm.lsp.ext.ByInheritingClass
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.intellij.openapi.application.readAction
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * *Show Inherited* in a `.by` file's structure: under a class, what `by/documentInheritedMembers` says
 * it inherits, greyed, matched to the class by its name's range.
 */
@TestFixtures
class ByInheritedMembersTest {

    private val fixture by codeInsightFixture()

    private val source = "class Base:\n    def a(self) -> int: ...\n\nclass Sub(Base):\n    pass\n"

    private fun range(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))

    /** A node of the platform's LSP structure, which carries `by`'s symbol as its value. */
    private fun node(symbol: DocumentSymbol) = object : StructureViewTreeElement {
        override fun getValue(): Any = symbol
        override fun getPresentation(): ItemPresentation = error("not drawn")
        override fun getChildren(): Array<TreeElement> = TreeElement.EMPTY_ARRAY
    }

    private val inherited = listOf(
        ByInheritingClass(range(0, 6, 10), listOf(ByInheritedMember("__doc__", SymbolKind.Field, containerName = "object"))),
        ByInheritingClass(
            range(3, 6, 9),
            listOf(
                ByInheritedMember("a", SymbolKind.Method, "(self) -> int", "Base", "shapes", "file:///shapes.by", range(1, 4, 27), range(1, 8, 9)),
                ByInheritedMember("__doc__", SymbolKind.Field, containerName = "object"),
            ),
        ),
    )

    @Test
    fun `a class lists what by says it inherits, greyed, and the fields filter takes its attributes`() = onEdt {
        val file = fixture.configureByText("shapes.by", source)
        ByInheritedMembers.getInstance(fixture.project).remember(file, inherited)
        val sub = DocumentSymbol("Sub", SymbolKind.Class, Range(Position(3, 0), Position(4, 8)), range(3, 6, 9), "(Base)")

        val nodes = withContext(Dispatchers.Default) { readAction { ByInheritedMembersProvider(file).provideNodes(node(sub)) } }

        assertEquals(listOf("a (self) -> int", "__doc__ null"), nodes.map { "${it.presentation.presentableText} ${it.presentation.locationString}" })
        assertEquals(CodeInsightColors.NOT_USED_ELEMENT_ATTRIBUTES, (nodes.first().presentation as ColoredItemPresentation).textAttributesKey)
        assertEquals(listOf(true, false), nodes.map { ByFieldsFilter.isVisible(it) })
    }

    @Test
    fun `a method inherits nothing`() = onEdt {
        val file = fixture.configureByText("shapes.by", source)
        ByInheritedMembers.getInstance(fixture.project).remember(file, inherited)
        val method = DocumentSymbol("a", SymbolKind.Method, range(1, 4, 27), range(1, 8, 9))
        val nodes = withContext(Dispatchers.Default) { readAction { ByInheritedMembersProvider(file).provideNodes(node(method)) } }
        assertFalse(nodes.isNotEmpty())
    }
}
