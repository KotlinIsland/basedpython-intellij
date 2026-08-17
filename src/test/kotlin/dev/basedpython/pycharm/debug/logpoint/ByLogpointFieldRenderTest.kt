package dev.basedpython.pycharm.debug.logpoint

import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.junit5.RunInEdt
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpointAdditionalInfo
import com.intellij.xdebugger.breakpoints.XLineBreakpointVerticalPlacement
import dev.basedpython.pycharm.debug.ByLineBreakpointType
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import java.awt.Container
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Paints the `Log:` box offscreen, so its shape can be checked without a running IDE.
 *
 * This exists because the box shipped twice looking wrong in ways no other test could see: once as a
 * field a few pixels wide, once as a bar a few pixels tall. Both were preferred-size mistakes, and
 * both are visible the moment the component is laid out and measured — which needs no window.
 *
 * The rendered PNGs land in `build/logpoint-field-{light,dark}.png` for a human — or a machine that
 * can read an image — to look at; the assertions are what fail the build.
 *
 * One thing the dark render does not capture: the caption paints the *editor's* background behind
 * itself to notch the box's border, and this fixture's editor keeps the default colour scheme
 * whatever `JBColor` is set to. So the chip is light in both images; in a dark IDE it follows the
 * editor.
 */
@TestFixtures
@RunInEdt(writeIntent = true)
class ByLogpointFieldRenderTest {

    private val fixture by codeInsightFixture()

    private val type get() = XDebuggerUtil.getInstance().findBreakpointType(ByLineBreakpointType::class.java)!!

    @Test
    fun `the box is the size of a box`() {
        fixture.configureByText("main.by", "a = 1\nprint(\"bye\")\n")
        val editor = fixture.editor as EditorEx
        val info = XLineBreakpointAdditionalInfo.Builder()
            .setVerticalPlacement(XLineBreakpointVerticalPlacement.INTER_LINE)
            .setSuspendPolicy(SuspendPolicy.NONE)
            .setLogExpressionIfEnabled("a")
            .build()
        val logpoint = XDebuggerManager.getInstance(fixture.project).breakpointManager
            .addLineBreakpoint(type, fixture.file.virtualFile.url, 1, null, info)

        val field = ByLogpointField.show(fixture.project, editor, logpoint)!!
        val panel = field.component
        val size = panel.preferredSize
        panel.setBounds(0, 0, size.width, size.height)
        layOut(panel)

        // A line of text plus its padding. The bar-shaped version measured about six pixels.
        assertTrue(
            size.height >= editor.lineHeight,
            "the box should be at least one line tall, was ${size.height} for a line height of ${editor.lineHeight}",
        )
        assertTrue(size.width >= 200, "the box should be wide enough to type an expression in, was ${size.width}")

        // Both themes, since the colours differ and dark is the one most people are looking at.
        listOf(false to "light", true to "dark").forEach { (dark, name) ->
            val wasDark = JBColor.isBright().not()
            JBColor.setDark(dark)
            try {
                val zoom = 3
                val image = BufferedImage(
                    maxOf(size.width, 1) * zoom,
                    maxOf(size.height, 1) * zoom,
                    BufferedImage.TYPE_INT_ARGB,
                )
                image.createGraphics().use {
                    // On the editor's own background, since the box is translucent by design and the
                    // caption paints that colour behind itself to notch the border. Against nothing,
                    // the notch is invisible and the whole thing is unjudgeable.
                    it.color = editor.colorsScheme.defaultBackground
                    it.fillRect(0, 0, image.width, image.height)
                    it.scale(zoom.toDouble(), zoom.toDouble())
                    panel.paint(it)
                }
                val png = File("build/logpoint-field-$name.png").apply { parentFile.mkdirs() }
                ImageIO.write(image, "png", png)
                assertTrue(png.length() > 0, "expected a rendered box at " + png.absolutePath)
            } finally {
                JBColor.setDark(wasDark)
            }
        }
        val caption = panel.components.first { it is JBLabel }
        // Swing paints the highest z-index first, so index 0 is what ends up on top. The caption was
        // added with a PALETTE_LAYER constraint that left it on layer 0 behind the box, which shaved
        // the top off every glyph — a clipped-text symptom with a z-order cause.
        assertEquals(0, panel.getComponentZOrder(caption), "the caption has to paint over the box, not under it")
        assertTrue(
            caption.height >= caption.getFontMetrics(caption.font).height,
            "the caption must be at least as tall as the text it paints, was " + caption.height,
        )
    }

    /** Lays out a component tree that was never added to a window, which is what `validate` needs a peer for. */
    private fun layOut(container: Container) {
        container.doLayout()
        container.components.filterIsInstance<Container>().forEach(::layOut)
    }

    private inline fun java.awt.Graphics2D.use(block: (java.awt.Graphics2D) -> Unit) {
        try {
            block(this)
        } finally {
            dispose()
        }
    }
}
