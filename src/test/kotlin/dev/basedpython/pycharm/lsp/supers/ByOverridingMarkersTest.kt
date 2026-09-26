package dev.basedpython.pycharm.lsp.supers

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lsp.ext.ByOverridingMember
import dev.basedpython.pycharm.lsp.ext.BySuperMember
import dev.basedpython.pycharm.testFramework.AnsweringClient
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.answered
import dev.basedpython.pycharm.testFramework.AnsweringClient.Companion.failed
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.util.BasedPythonBundle
import kotlinx.coroutines.runBlocking
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.messages.ResponseErrorCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The gutter's *overrides* and *implements* icons decide nothing about the source: which members
 * override what, and whether any of it is abstract, is `by`'s. What is left here is putting an icon
 * on the right token with the right face, and never drawing an answer about other text.
 */
@TestFixtures
class ByOverridingMarkersTest {

    private val fixture by codeInsightFixture()

    private var index = 0

    /**
     * ```
     * 0 class A(Base, Greeter):
     * 1     def f(self): ...
     * 2     def greet(self): ...
     * 3     size = 2
     * ```
     */
    private val source = "class A(Base, Greeter):\n    def f(self): ...\n    def greet(self): ...\n    size = 2\n"

    private fun file(): PsiFile = fixture.addFileToProject("pkg/members${index++}.by", source)

    private fun name(line: Int, start: Int, end: Int) = Range(Position(line, start), Position(line, end))

    private fun overridden(container: String, member: String, line: Int, abstract: Boolean = false) =
        BySuperMember(member, container, "file:///bases.by", name(line, 4, 20), name(line, 8, 8 + member.length), abstract = abstract)

    private val f = ByOverridingMember("f", "A", name(1, 8, 9), superMembers = listOf(overridden("Base", "f", 3)))
    private val greet = ByOverridingMember(
        "greet", "A", name(2, 8, 13), superMembers = listOf(overridden("Greeter", "greet", 7, abstract = true)),
    )

    private fun markers(file: PsiFile, members: List<ByOverridingMember>): List<LineMarkerInfo<PsiElement>> {
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val elements = PsiTreeUtil.collectElements(file) { true }.toHashSet()
        return ByOverridingMarkers.markers(file, document, members, elements)
    }

    private val LineMarkerInfo<PsiElement>.tooltip: String? get() = lineMarkerTooltip

    @Test
    fun `a member that overrides gets the overriding icon on its name, on the right`() = onEdt {
        val file = file()
        val marker = markers(file, listOf(f)).single()

        assertEquals("f", marker.element!!.text)
        assertEquals(source.indexOf("f(self)"), marker.startOffset)
        assertSame(AllIcons.Gutter.OverridingMethod, marker.icon)
        assertEquals(GutterIconRenderer.Alignment.RIGHT, marker.createGutterRenderer().alignment)
        assertEquals(BasedPythonBundle.message("overriding.markers.overrides", "Base.f"), marker.tooltip)
    }

    @Test
    fun `a member that overrides only abstract members implements them`() = onEdt {
        val marker = markers(file(), listOf(greet)).single()

        assertSame(AllIcons.Gutter.ImplementingMethod, marker.icon)
        assertEquals(BasedPythonBundle.message("overriding.markers.implements", "Greeter.greet"), marker.tooltip)
    }

    @Test
    fun `an abstract member overriding an abstract one still leaves it to be implemented`() = onEdt {
        val marker = markers(file(), listOf(greet.copy(abstract = true))).single()
        assertSame(AllIcons.Gutter.OverridingMethod, marker.icon)
    }

    @Test
    fun `a member overriding a concrete and an abstract member overrides, and names both`() = onEdt {
        val both = f.copy(superMembers = listOf(overridden("Base", "f", 3), overridden("Greeter", "f", 7, abstract = true)))
        val marker = markers(file(), listOf(both)).single()

        assertSame(AllIcons.Gutter.OverridingMethod, marker.icon)
        assertEquals(BasedPythonBundle.message("overriding.markers.overrides", "Base.f, Greeter.f"), marker.tooltip)
    }

    @Test
    fun `a class attribute gets the icon on the name it assigns`() = onEdt {
        val size = ByOverridingMember("size", "A", name(3, 4, 8), superMembers = listOf(overridden("Base", "size", 1)))
        val marker = markers(file(), listOf(size)).single()
        assertEquals("size", marker.element!!.text)
    }

    @Test
    fun `only members whose names are among the pass's elements are marked`() = onEdt {
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        val onlyGreet = setOf(file.findElementAt(source.indexOf("greet"))!!)

        val markers = ByOverridingMarkers.markers(file, document, listOf(f, greet), onlyGreet)

        assertEquals(listOf("greet"), markers.map { it.element!!.text })
    }

    @Test
    fun `nothing is drawn for a member with nowhere to go`() = onEdt {
        val nowhere = f.copy(superMembers = listOf(BySuperMember("f", "Base")))
        assertTrue(markers(file(), listOf(nowhere)).isEmpty())
    }

    /**
     * The pass is served what `by` said for the text as it is, and nothing once the text has moved
     * on: an answer a keystroke old would put icons on the wrong lines. With no server to ask again,
     * that is no icons at all.
     */
    @Test
    fun `an answer is drawn for its own revision and not after an edit`() = onEdt {
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        ByOverridingMembers.getInstance(fixture.project).remember(document, file.virtualFile, listOf(f, greet))

        assertEquals(listOf("f", "greet"), slowMarkers(file).map { it.element!!.text })

        WriteCommandAction.runWriteCommandAction(fixture.project) { document.insertString(0, "\n") }
        PsiDocumentManager.getInstance(fixture.project).commitDocument(document)

        assertTrue(slowMarkers(file).isEmpty())
    }

    /** What the provider draws for [file], collected as the pass does: off the EDT, in a read action. */
    private fun slowMarkers(file: PsiFile): List<LineMarkerInfo<*>> =
        ApplicationManager.getApplication().executeOnPooledThread<List<LineMarkerInfo<*>>> {
            ReadAction.computeBlocking<List<LineMarkerInfo<*>>, RuntimeException> {
                val result = mutableListOf<LineMarkerInfo<*>>()
                ByOverridingMarkers().collectSlowLineMarkers(PsiTreeUtil.collectElements(file) { true }.toList(), result)
                result
            }
        }.get(10, TimeUnit.SECONDS)

    private fun ask(answer: (String) -> CompletableFuture<*>): Pair<ByOverridingMembers.Asked, List<String>> {
        val asked = mutableListOf<String>()
        val client = AnsweringClient(fixture.project) { method -> asked += method; answer(method) }
        val file = file()
        val document = PsiDocumentManager.getInstance(fixture.project).getDocument(file)!!
        return runBlocking { ByOverridingMembers.ask(client, file.virtualFile, document) } to asked
    }

    @Test
    fun `one request answers every member of the document`() = onEdt {
        val (answer, asked) = ask { answered(listOf(f, greet)) }
        assertEquals(ByOverridingMembers.Asked.Answered(listOf(f, greet)), answer)
        assertEquals(listOf("by/documentSuperMembers"), asked)
    }

    @Test
    fun `a by that declines is a document with nothing to draw`() = onEdt {
        val (answer, _) = ask { answered<Any>(null) }
        assertEquals(ByOverridingMembers.Asked.Answered(emptyList()), answer)
    }

    @Test
    fun `a by without the request is told apart from one that failed`() = onEdt {
        val (unknown, asked) = ask { failed(ResponseErrorCode.MethodNotFound, "Unhandled method by/documentSuperMembers") }
        assertEquals(ByOverridingMembers.Asked.Unknown, unknown)
        assertEquals(listOf("by/documentSuperMembers"), asked)

        val (failed, _) = ask { failed(ResponseErrorCode.InternalError) }
        assertEquals(ByOverridingMembers.Asked.Failed, failed)
    }
}
