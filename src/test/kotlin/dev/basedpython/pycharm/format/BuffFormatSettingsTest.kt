package dev.basedpython.pycharm.format

import com.intellij.application.options.CodeStyle
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.modifier.TransientCodeStyleSettings
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.lsp.ext.BuffFormatSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `.by` file's right margin and indent follow what `buff/formatSettings` says `buff` formats it
 * with, and the buff code style tab says what that is.
 */
@TestFixtures
class BuffFormatSettingsTest {

    private val fixture by codeInsightFixture()

    private val buff = BuffFormatSettings(
        lineLength = 100,
        indentWidth = 2,
        indentStyle = "space",
        quoteStyle = "single",
        lineEnding = "lf",
        configurationFile = null,
    )

    @Test
    fun `the right margin and indent are buff's`() = onEdt {
        val file = fixture.configureByText("a.by", "x = 1\n")
        BuffFormatSettingsService.getInstance(fixture.project).remember(file.virtualFile, buff)

        val settings = TransientCodeStyleSettings(file.virtualFile, fixture.project, CodeStyle.getSettings(fixture.project))
        assertTrue(BuffCodeStyleSettingsModifier().modifySettings(settings, file))
        val common = settings.getCommonSettings(BasedPythonLanguage)
        assertEquals(100, common.RIGHT_MARGIN)
        assertEquals(2, common.indentOptions!!.INDENT_SIZE)
        assertFalse(common.indentOptions!!.USE_TAB_CHARACTER)
    }

    @Test
    fun `a file buff has said nothing about keeps the page's values`() = onEdt {
        val file = fixture.configureByText("b.by", "x = 1\n")
        val settings = TransientCodeStyleSettings(file.virtualFile, fixture.project, CodeStyle.getSettings(fixture.project))
        assertFalse(BuffCodeStyleSettingsModifier().modifySettings(settings, file))
    }

    @Test
    fun `the tab says what buff resolved, and that no file configured it`() = onEdt {
        val file = fixture.configureByText("a.by", "x = 1\n").virtualFile
        val text = BuffResolvedSettings.describe(fixture.project, file, buff)
        assertEquals(
            "<html>buff formats <b>a.by</b> with a line length of <b>100</b>, <b>2-space indents</b>, <b>single</b> quotes " +
                "and <b>lf</b> line endings, its defaults, since no configuration file sets them.</html>",
            text,
        )
    }
}
