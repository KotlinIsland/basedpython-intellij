package dev.basedpython.pycharm.navigation

import com.intellij.ide.structureView.StructureViewModel
import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.util.treeView.smartTree.Sorter
import com.intellij.navigation.ItemPresentation
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

/**
 * The `.by` structure has PyCharm's Alphabetical sorter and Show Fields filter on top of the
 * platform's LSP symbols, and `by`'s workspace symbols are placed in their container and matched by
 * their qualified name.
 */
@TestFixtures
class ByStructureAndSymbolsTest {

    private val fixture by codeInsightFixture()

    private fun node(kind: SymbolKind) = object : StructureViewTreeElement {
        override fun getValue(): Any = DocumentSymbol("x", kind, Range(Position(0, 0), Position(0, 1)), Range(Position(0, 0), Position(0, 1)))
        override fun getPresentation(): ItemPresentation = throw UnsupportedOperationException()
        override fun getChildren(): Array<StructureViewTreeElement> = emptyArray()
    }

    @Test
    fun `Show Fields hides the attributes of classes and nothing else`() {
        assertFalse(ByFieldsFilter.isVisible(node(SymbolKind.Field)))
        for (kind in listOf(SymbolKind.Method, SymbolKind.Property, SymbolKind.Class, SymbolKind.Function, SymbolKind.Variable)) {
            assertTrue(ByFieldsFilter.isVisible(node(kind)), "$kind")
        }
        assertTrue(ByFieldsFilter.isReverted(), "shown while the filter is off")
    }

    @Test
    fun `the model sorts alphabetically, filters fields and adds inherited members, the rest being the platform's`() = onEdt {
        val platform = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(StructureViewModel::class.java)) { _, method, _ ->
            when (method.name) {
                "getSorters", "getFilters", "getGroupers" -> java.lang.reflect.Array.newInstance(method.returnType.componentType, 0)
                else -> null
            }
        } as StructureViewModel
        val model = ByStructureViewModel(platform, fixture.configureByText("shapes.by", ""))
        assertEquals(listOf(Sorter.ALPHA_SORTER), model.sorters.toList())
        assertEquals(listOf(ByFieldsFilter), model.filters.toList())
        assertEquals(listOf("SHOW_INHERITED"), model.nodeProviders.map { it.name }, "the platform's own toggle's name, shared with every language")
    }

    @Test
    fun `a workspace symbol is placed in its container and named by its qualified name`() = onEdt {
        val client = AnsweringClient(fixture.project) { answered(null) }
        val symbol = WorkspaceSymbol("area", SymbolKind.Method, Either.forLeft(Location("file:///shapes/base.by", Range(Position(4, 8), Position(4, 12)))), "shapes.base.Circle")
        val item = ByWorkspaceSymbols.createNavigationItem(client, symbol) as ByWorkspaceSymbolItem

        assertEquals("area", item.presentation.presentableText)
        assertEquals("shapes.base.Circle", item.presentation.locationString)
        assertEquals("shapes.base.Circle.area", ByQualifiedSymbolNames().getQualifiedName(item))
        assertEquals(".", ByQualifiedSymbolNames().qualifiedNameSeparator)
    }

    @Test
    fun `a symbol with no container is placed in its file`() = onEdt {
        val client = AnsweringClient(fixture.project) { answered(null) }
        val symbol = WorkspaceSymbol("main", SymbolKind.Function, Either.forLeft(Location("file:///app/main.by", Range(Position(0, 4), Position(0, 8)))))
        val item = ByWorkspaceSymbols.createNavigationItem(client, symbol) as ByWorkspaceSymbolItem

        assertEquals("main.by", item.presentation.locationString)
        assertEquals("main", item.qualifiedName)
        assertNull(item.container)
    }
}
