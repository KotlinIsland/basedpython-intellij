package dev.basedpython.pycharm.lsp.inlay

import com.intellij.codeInsight.hints.InlayHintsSettings
import com.intellij.testFramework.junit5.fixture.TestFixtures
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** basedpython on *Settings | Editor | Inlay Hints*: its options are the kinds' modes, kept where basedpython's page keeps them. */
@TestFixtures
class ByInlaySettingsModelTest {

    private val fixture by codeInsightFixture()

    private val settings get() = BasedPythonSettings.getInstance(fixture.project)

    @AfterEach
    fun restore() = onEdt {
        settings.loadState(BasedPythonSettings.State())
        InlayHintsSettings.instance().setHintsEnabledForLanguage(BasedPythonLanguage, true)
    }

    @Test
    fun `an option for each kind, checked while its kind is shown, and unchecking it is never`() = onEdt {
        settings.setInlayMode(ByHintKind.VARIABLE_TYPES, ByHintMode.ON_PUSH)
        settings.setInlayMode(ByHintKind.ENUM_VALUES, ByHintMode.NEVER)
        val model = ByInlaySettingsModel(fixture.project)
        assertEquals(ByHintKind.entries.map { it.display }, model.cases.map { it.name })
        val variables = model.cases.first { it.id == "variableTypes" }
        val enums = model.cases.first { it.id == "enumValues" }
        assertTrue(variables.value)
        assertFalse(enums.value)

        variables.value = false
        enums.value = true
        assertTrue(model.isModified())
        // nothing is stored before Apply
        assertEquals(ByHintMode.ON_PUSH, settings.inlayMode(ByHintKind.VARIABLE_TYPES))

        model.apply()
        assertEquals(ByHintMode.NEVER, settings.inlayMode(ByHintKind.VARIABLE_TYPES))
        assertEquals(ByHintMode.ALWAYS, settings.inlayMode(ByHintKind.ENUM_VALUES))
        assertFalse(model.isModified())
    }

    @Test
    fun `checking a kind again before Apply gives it back the mode it had`() = onEdt {
        settings.setInlayMode(ByHintKind.VARIABLE_TYPES, ByHintMode.ON_PUSH)
        val model = ByInlaySettingsModel(fixture.project)
        val variables = model.cases.first { it.id == "variableTypes" }
        variables.value = false
        variables.value = true
        assertFalse(model.isModified())
    }

    @Test
    fun `the entry's own checkbox is the platform's switch for the language`() = onEdt {
        val model = ByInlaySettingsModel(fixture.project)
        model.isEnabled = false
        assertTrue(model.isModified())
        model.apply()
        assertFalse(InlayHintsSettings.instance().hintsEnabled(BasedPythonLanguage))
    }
}
