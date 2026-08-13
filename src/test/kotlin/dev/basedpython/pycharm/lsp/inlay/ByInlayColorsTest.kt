package dev.basedpython.pycharm.lsp.inlay

import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * The colours a scheme that says nothing about [ByInlayColors.HINT] gets — which is every scheme but
 * the two bundled ones, so this is what a hint looks like for almost everybody.
 *
 * Pure: both derivations are colour arithmetic, and asserting the *properties* that make a hint
 * legible beats pinning the six hex digits they happen to produce today.
 */
class ByInlayColorsTest {

    private val darcula = Color(0x2b, 0x2b, 0x2b) to Color(0xbb, 0xbb, 0xbb)
    private val light = Color(0xff, 0xff, 0xff) to Color(0x00, 0x00, 0x00)

    private fun Color.distanceTo(other: Color): Int =
        (red - other.red) * (red - other.red) +
            (green - other.green) * (green - other.green) +
            (blue - other.blue) * (blue - other.blue)

    @Test
    fun `hint text is dimmer than code but is not the background`() {
        for ((background, text) in listOf(darcula, light)) {
            val hint = ByInlayColors.derivedForeground(text, background)
            assertTrue(
                hint.distanceTo(background) < text.distanceTo(background),
                "a hint should be quieter than code: $hint vs $text on $background",
            )
            assertNotEquals(background, hint, "a hint faded into invisibility is not a hint")
        }
    }

    @Test
    fun `the tint is distinct from the editor background — the whole reason it exists`() {
        // Faded-and-untinted is how the IDE draws unused code. If the tint were not perceptible the
        // hint would be wearing dead code's clothes, which is the bug this exists to prevent.
        for ((background, text) in listOf(darcula, light)) {
            val tint = ByInlayColors.derivedBackground(
                ByInlayColors.derivedForeground(text, background),
                background,
            )
            assertNotEquals(background, tint)
            assertTrue(
                tint.distanceTo(background) > 25,
                "tint $tint is not far enough from background $background to be seen",
            )
        }
    }

    @Test
    fun `the tint stays nearer the background than the text does`() {
        // A tint that outruns the text colour stops being a tint and becomes a filled label.
        for ((background, text) in listOf(darcula, light)) {
            val hint = ByInlayColors.derivedForeground(text, background)
            val tint = ByInlayColors.derivedBackground(hint, background)
            assertTrue(
                tint.distanceTo(background) < hint.distanceTo(background),
                "tint $tint should sit between background $background and hint text $hint",
            )
        }
    }

    @Test
    fun `the tint follows the text colour, so it stays neutral against a coloured background`() {
        val background = Color(0x1e, 0x20, 0x2c)
        val warm = ByInlayColors.derivedBackground(Color(0xd0, 0x90, 0x40), background)
        val cool = ByInlayColors.derivedBackground(Color(0x40, 0x90, 0xd0), background)
        assertNotEquals(warm, cool, "the tint is a blend of the hint's own colour, not a fixed grey")
    }
}
