package dev.basedpython.pycharm.editor

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.GutterMark
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.hints.presentation.PresentationRenderer
import com.intellij.icons.AllIcons
import com.intellij.navigation.GotoRelatedItem
import com.intellij.openapi.application.UI
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.GutterMarkPreprocessor
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.debug.dfa.ByDataFlowVerdictRenderer
import dev.basedpython.pycharm.editor.highlight.ByStringMarginRenderer
import dev.basedpython.pycharm.lsp.counterparts.ByCounterpartMarkerInfo
import dev.basedpython.pycharm.lsp.counterparts.ByStubMarkFilter
import dev.basedpython.pycharm.lsp.inlay.ByBlockHintPresentation
import dev.basedpython.pycharm.lsp.inlay.ByInlayHintPresentation
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import dev.basedpython.pycharm.testFramework.withLocksForbidden
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.image.BufferedImage
import javax.swing.JComponent

/**
 * That what this plugin puts into an editor is drawn without a lock, as the platform requires of
 * anything reached from paint.
 *
 * `EditorComponentImpl.paintComponent` runs the editor's painting under `withLocksSoftlyProhibited`,
 * and `EditorTabs.paintChildren` everything inside an editor tab, the gutter among it; a lock taken
 * there is reported to the IDE's error log and the paint goes on. So a renderer that reached for the
 * PSI or a read action would draw correctly and leave a line in the log, which is why this paints a
 * real editor carrying each of them rather than trusting that they read only what they hold: the
 * inline and block hints drawn for `by`'s inlay hints, a multiline string's trim margin, a debug
 * stop's `= true` in the margin, and a stub icon in the gutter beside one of the Python plugin's,
 * which the gutter can ask `ByPyiMarkerFilter` to thin out as it paints: `paintIcons` gathers a
 * line's icons through `buildGutterRenderersCache` whenever they changed since it last did, and runs
 * every `GutterMarkPreprocessor` over them there (`EditorGutterComponentImpl`).
 *
 * Painted from `Dispatchers.UI`, the EDT without the write-intent lock, as a paint event runs: the
 * platform wraps only input events in that lock (`IdeEventQueue`), and under it code that merely
 * asserts read access would pass here and throw in the IDE.
 */
@TestFixtures
class ByEditorPaintLocksTest {

    private val fixture by codeInsightFixture()

    private val module = """
        |class A(B):
        |    def f(self, a):
        |        if a == 2:
        |            return '''
        |                one
        |                two
        |            '''
        |""".trimMargin()

    @Test
    fun `the plugin's hints, margins, verdicts and gutter icons are painted without a lock`() {
        lateinit var editor: Editor
        onEdt {
            fixture.configureByText("mod.by", module)
            editor = fixture.editor
            val inline = ByInlayHintPresentation(editor = editor, text = ": int", padLeft = true, padRight = false)
            editor.inlayModel.addInlineElement(module.indexOf("a)") + 1, false, PresentationRenderer(inline))
            val block = ByBlockHintPresentation(editor, "\n    def g(self, a: int) -> None: ...")
            editor.inlayModel.addBlockElement(editor.document.getLineEndOffset(1), true, false, 0, BlockRenderer(block))

            val markup = editor.markupModel
            val string = module.indexOf("'''")
            markup.addRangeHighlighter(
                null, string, module.lastIndexOf("'''") + 3, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE,
            ).customRenderer = ByStringMarginRenderer(indent = 16)
            val condition = module.indexOf("a == 2")
            markup.addRangeHighlighter(
                null, condition, condition + "a == 2".length, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE,
            ).customRenderer = ByDataFlowVerdictRenderer("= true")

            // `class A`: this plugin's stub icon and PyCharm's on the same name
            val name = fixture.file.findElementAt(module.indexOf("A("))!!
            val range = TextRange.from(name.textRange.startOffset, 1)
            marks = listOf(ours(name, range), StandInProvider().marker(name, range)).map { info ->
                val mark = info.createGutterRenderer()!!
                markup.addRangeHighlighter(null, range.startOffset, range.endOffset, HighlighterLayer.ADDITIONAL_SYNTAX, HighlighterTargetArea.EXACT_RANGE)
                    .gutterIconRenderer = mark
                mark
            }
        }

        val kept = timeoutRunBlocking(context = Dispatchers.UI) {
            withLocksForbidden {
                paint(editor.contentComponent)
                paint((editor as EditorEx).gutterComponentEx)
                // what the gutter asks while painting a line whose icons it has not gathered since
                // they last changed; when that is, is the gutter's to decide, so it is asked here
                stubFilter.processMarkers(marks)
            }
        }

        assertEquals(listOf(marks[0]), kept)
    }

    private lateinit var marks: List<GutterMark>

    private fun ours(leaf: PsiElement, range: TextRange): LineMarkerInfo<PsiElement> =
        ByCounterpartMarkerInfo(leaf, range, "Has stub in mod.pyi", GutterIconNavigationHandler { _, _ -> })

    /**
     * `ByPyiMarkerFilter` against a stand-in for PyCharm's provider, which the tests have no Python
     * plugin to load: the filter itself is `ByStubMarkFilter`, which this calls as it does.
     */
    private val stubFilter = object : GutterMarkPreprocessor {
        override fun processMarkers(list: List<GutterMark>): List<GutterMark> =
            ByStubMarkFilter.process(list, StandInProvider::class.java.name, AllIcons.Gutter.Unique)
    }

    /** Builds icons as `PyiRelatedItemLineMarkerProvider.createLineMarkerInfo` does. */
    private class StandInProvider {
        fun marker(element: PsiElement, range: TextRange): RelatedItemLineMarkerInfo<PsiElement> =
            RelatedItemLineMarkerInfo(
                element, range, AllIcons.Gutter.Unique, { "Has stub item in mod.pyi" },
                { _, _ -> }, GutterIconRenderer.Alignment.RIGHT, { emptyList<GotoRelatedItem>() },
            )
    }

    private fun paint(component: JComponent) {
        component.setBounds(0, 0, WIDTH, HEIGHT)
        val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            component.paint(g)
        } finally {
            g.dispose()
        }
    }

    /** A block hint at its own height, as the platform's block inlay gives it the room. */
    private class BlockRenderer(private val block: ByBlockHintPresentation) : EditorCustomElementRenderer {
        override fun calcWidthInPixels(inlay: Inlay<*>): Int = block.width
        override fun calcHeightInPixels(inlay: Inlay<*>): Int = block.height
        override fun paint(inlay: Inlay<*>, g: Graphics, targetRegion: Rectangle, textAttributes: TextAttributes) {
            val g2 = g.create() as Graphics2D
            try {
                g2.translate(targetRegion.x, targetRegion.y)
                block.paint(g2, textAttributes)
            } finally {
                g2.dispose()
            }
        }
    }

    private companion object {
        const val WIDTH = 800
        const val HEIGHT = 400
    }
}
