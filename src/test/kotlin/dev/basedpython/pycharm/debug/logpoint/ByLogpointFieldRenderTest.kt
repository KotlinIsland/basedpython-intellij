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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.intellij.ui.JBColor
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
 * The rendered PNG lands in `build/logpoint-field.png` for a human (or a machine that can read an
 * image) to look at; the assertions are what fail the build.
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
                val image = BufferedImage(maxOf(size.width, 1), maxOf(size.height, 1), BufferedImage.TYPE_INT_ARGB)
                image.createGraphics().use { panel.paint(it) }
                val png = File("build/logpoint-field-$name.png").apply { parentFile.mkdirs() }
                ImageIO.write(image, "png", png)
                assertTrue(png.length() > 0, "expected a rendered box at " + png.absolutePath)
            } finally {
                JBColor.setDark(wasDark)
            }
        }
        println("logpoint field rendered at " + size.width + "x" + size.height + ", line height " + editor.lineHeight)
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
