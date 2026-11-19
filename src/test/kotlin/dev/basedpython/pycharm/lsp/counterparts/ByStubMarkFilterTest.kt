package dev.basedpython.pycharm.lsp.counterparts

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.MergeableLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PyCharm's stub icon taken out of a line where this plugin's is on the same name, and nowhere else —
 * against a stand-in for `PyiRelatedItemLineMarkerProvider`, which the tests have no Python plugin
 * to load: one that builds its icons the way it does, a `RelatedItemLineMarkerInfo` with the
 * `Unique` icon and a lambda of its own to navigate.
 */
@TestFixtures
class ByStubMarkFilterTest {

    private val fixture by codeInsightFixture()

    /** Builds icons as `PyiRelatedItemLineMarkerProvider.createLineMarkerInfo` does. */
    private class StandInProvider {
        fun marker(element: PsiElement, range: TextRange): RelatedItemLineMarkerInfo<PsiElement> =
            RelatedItemLineMarkerInfo(
                element, range, AllIcons.Gutter.Unique, { "Has stub item in mod.pyi" },
                { _, _ -> }, GutterIconRenderer.Alignment.RIGHT, { emptyList<GotoRelatedItem>() },
            )
    }

    private val provider = StandInProvider::class.java.name

    private fun filter(marks: List<GutterMark>) = ByStubMarkFilter.process(marks, provider, AllIcons.Gutter.Unique)

    private fun leaf(): PsiElement {
        val file = fixture.configureByText("mod.by", "def f(): ...\ndef g(): ...\n")
        return file.findElementAt(4)!!
    }

    private fun ours(leaf: PsiElement, range: TextRange) =
        ByCounterpartMarkerInfo(leaf, range, "Has stub in mod.pyi", GutterIconNavigationHandler { _, _ -> })

    private fun mark(info: LineMarkerInfo<*>): GutterMark = info.createGutterRenderer()!!

    @Test
    fun `the stand-in navigates the way PyCharm's provider does, with a lambda of its own`() = onEdt {
        val handler = StandInProvider().marker(leaf(), TextRange(4, 5)).navigationHandler!!
        assertTrue(handler.javaClass.name.startsWith("$provider\$"), handler.javaClass.name)
    }

    @Test
    fun `PyCharm's icon on a name this plugin's is on is taken away`() = onEdt {
        val leaf = leaf()
        val ours = mark(ours(leaf, TextRange(4, 5)))
        val theirs = mark(StandInProvider().marker(leaf, TextRange(4, 5)))
        assertEquals(listOf(ours), filter(listOf(theirs, ours)))
    }

    @Test
    fun `PyCharm's icon stays where this plugin has none, or has one on another name`() = onEdt {
        val leaf = leaf()
        val theirs = mark(StandInProvider().marker(leaf, TextRange(4, 5)))
        assertEquals(listOf(theirs), filter(listOf(theirs)))

        val elsewhere = mark(ours(leaf, TextRange(17, 18)))
        assertEquals(listOf(theirs, elsewhere), filter(listOf(theirs, elsewhere)))
    }

    @Test
    fun `another provider's icon stays, the same picture or not`() = onEdt {
        val leaf = leaf()
        val ours = mark(ours(leaf, TextRange(4, 5)))
        val sameIcon = mark(
            RelatedItemLineMarkerInfo(
                leaf, TextRange(4, 5), AllIcons.Gutter.Unique, { "something else" },
                GutterIconNavigationHandler { _, _ -> }, GutterIconRenderer.Alignment.RIGHT, { emptyList<GotoRelatedItem>() },
            ),
        )
        val overridden = mark(LineMarkerInfo(leaf, TextRange(4, 5), AllIcons.Gutter.OverridenMethod, null, null, GutterIconRenderer.Alignment.RIGHT) { "" })
        assertEquals(listOf(sameIcon, overridden, ours), filter(listOf(sameIcon, overridden, ours)))
    }

    /** The daemon merges icons of one kind on one line; merged, they are still PyCharm's. */
    @Test
    fun `PyCharm's icons merged into one are taken away together`() = onEdt {
        val leaf = leaf()
        val merged = MergeableLineMarkerInfo.merge(
            listOf(StandInProvider().marker(leaf, TextRange(4, 5)), StandInProvider().marker(leaf, TextRange(4, 5))),
            0,
        ).single()
        assertTrue(MergeableLineMarkerInfo.getMergedMarkers(merged).size == 2)
        val ours = mark(ours(leaf, TextRange(4, 5)))
        assertEquals(listOf(ours), filter(listOf(mark(merged), ours)))
    }
}
