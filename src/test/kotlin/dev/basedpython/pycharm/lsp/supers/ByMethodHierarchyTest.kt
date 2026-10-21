package dev.basedpython.pycharm.lsp.supers

import com.intellij.ide.hierarchy.LanguageMethodHierarchy
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.navigation.ByDeclarationElement
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The method hierarchy decides nothing about overriding: up is `by/superMembers`, down is
 * `textDocument/implementation`, and which class a member is in is `by`'s outline. What is left here
 * is the tree those answers make: the line up from the base, the base, and below it each member
 * under the one it overrides directly.
 */
@TestFixtures
class ByMethodHierarchyTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `the provider is registered for basedpython, so Ctrl+Shift+H is no longer greyed out there`() = onEdt {
        assertInstanceOf(ByMethodHierarchyProvider::class.java, LanguageMethodHierarchy.INSTANCE.forLanguage(BasedPythonLanguage))
    }

    @Test
    fun `with no by to ask there is no target, and the action stays as it was`() = onEdt {
        fixture.configureByText("shapes.by", "class Shape:\n    def area(self): ...\n")
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, fixture.project)
            .add(CommonDataKeys.EDITOR, fixture.editor)
            .build()
        assertNull(ByMethodHierarchyProvider().getTarget(context))
    }

    private fun file(name: String): VirtualFile = fixture.addFileToProject(name, "").virtualFile

    private fun declared(file: VirtualFile, container: String, line: Int, abstract: Boolean = false) =
        ByDeclared(file, file.url, Range(Position(line, 8), Position(line, 12)), container, "area", file.name, abstract)

    @Test
    fun `the supers stand in a line above the base, the topmost at the root`() = onEdt {
        val bases = file("bases.by")
        val circles = file("circles.by")
        val shape = declared(bases, "Shape", 1, abstract = true)
        val round = declared(bases, "Round", 5)
        val circle = declared(circles, "Circle", 3)

        val tree = ByOverrideHierarchy.assemble(circle, supers = listOf(round, shape), parents = emptyMap())

        assertEquals(shape, tree.root.declared)
        assertEquals(listOf(round), tree.root.children.map { it.declared })
        val base = tree.root.children.single().children.single()
        assertSame(tree.base, base)
        assertEquals(circle, base.declared)
        assertEquals(true, base.isBase)
    }

    @Test
    fun `below the base each member hangs from what it overrides directly, by file and then in order`() = onEdt {
        val circles = file("circles.by")
        val rings = file("rings.by")
        val circle = declared(circles, "Circle", 3)
        val ring = declared(rings, "Ring", 7)
        val thinRing = declared(rings, "ThinRing", 12)
        val disc = declared(circles, "Disc", 9)
        val oval = declared(circles, "Oval", 20)

        val tree = ByOverrideHierarchy.assemble(
            circle,
            supers = emptyList(),
            parents = mapOf(ring to circle, thinRing to ring, oval to circle, disc to circle),
        )

        assertSame(tree.root, tree.base)
        assertEquals(listOf("Disc", "Oval", "Ring"), tree.base.children.map { it.declared.container })
        assertEquals(listOf("ThinRing"), tree.base.children.last().children.map { it.declared.container })
    }

    @Test
    fun `why there is no tree is a leaf, not another root that asks by again`() = onEdt {
        // the message node was taken for the hidden root, so every expansion asked `by` once more and
        // nested the same message under itself: 587 rows, measured
        val file = fixture.configureByText("shapes.by", "def free(): ...\n")
        val target = ByDeclarationElement(file, 0, "", null, null)
        val structure = ByMethodHierarchyTreeStructure(fixture.project, AnsweringClient(fixture.project) { answered(null) }, target) {}
        val root = structure.baseDescriptor as ByMethodNodeDescriptor
        val message = ByMethodNodeDescriptor(fixture.project, root, target, isBase = false, node = null, message = "nothing here")

        assertEquals(0, structure.getChildElements(message).size)
    }

    @Test
    fun `a tree item is named Class dot member, and the member alone where by does not say its class`() = onEdt {
        val circles = file("circles.by")
        assertEquals("Circle.area", declared(circles, "Circle", 3).name)
        assertEquals("area", declared(circles, "Circle", 3).copy(container = null).name)
    }
}
