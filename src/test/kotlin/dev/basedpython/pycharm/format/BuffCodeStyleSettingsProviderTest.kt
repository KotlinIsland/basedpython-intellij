package dev.basedpython.pycharm.format

import com.intellij.psi.codeStyle.CodeStyleSettingsManager
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The basedpython code style page keeps no buff options of its own: buff reads them from the
 * project's configuration, and a line length or quote style kept here was read by nothing.
 */
@TestFixtures
class BuffCodeStyleSettingsProviderTest {

    private val fixture by codeInsightFixture()

    @Test
    fun `no shadow of buff's options is kept in the IDE's code style`() = onEdt {
        val settings = CodeStyleSettingsManager.getInstance(fixture.project).createSettings()
        assertNull(BuffCodeStyleSettingsProvider().createCustomSettings(settings))
    }
}
