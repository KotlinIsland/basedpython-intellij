package dev.basedpython.pycharm.lsp.imports

import com.intellij.application.options.editor.AutoImportOptionsProviderEP
import com.intellij.codeInsight.CodeInsightSettings
import com.intellij.openapi.ui.ComboBox
import com.intellij.testFramework.junit5.fixture.TestFixtures
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.ui.components.JBCheckBox
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.settings.app.BasedPythonAppSettings
import dev.basedpython.pycharm.testFramework.codeInsightFixture
import dev.basedpython.pycharm.testFramework.onEdt
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * basedpython's options on the Auto Import page, and the one rule about them that is not just a
 * binding: the paste option is the platform's value, which another language's page can show too,
 * and this one must not write back what it showed over a change made there.
 */
@TestFixtures
class ByAutoImportOptionsTest {

    private val fixture by codeInsightFixture()

    private val settings get() = CodeInsightSettings.getInstance()
    private var pasteSetting = CodeInsightSettings.YES
    private var onTheFly = false

    @BeforeEach
    fun keep() {
        pasteSetting = settings.ADD_IMPORTS_ON_PASTE
        onTheFly = BasedPythonAppSettings.getInstance().addUnambiguousImportsOnTheFly
    }

    /** The pages each test made, whose UI is disposed after it as the Settings dialog would. */
    private val created = mutableListOf<ByAutoImportOptions>()

    @AfterEach
    fun restore() {
        runInEdtAndWait { created.forEach { it.disposeUIResources() } }
        settings.ADD_IMPORTS_ON_PASTE = pasteSetting
        BasedPythonAppSettings.getInstance().addUnambiguousImportsOnTheFly = onTheFly
    }

    @Test
    fun `the options are on the Auto Import page, after every other language's`() {
        val providers = AutoImportOptionsProviderEP.EP_NAME.getExtensions(fixture.project)
        assertTrue(providers.last().instanceClass == ByAutoImportOptions::class.java.name)
    }

    @Test
    fun `a paste setting changed elsewhere on the page is not written over`() = onEdt {
        settings.ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
        val options = ByAutoImportOptions().also { created += it }
        options.createComponent()
        options.reset()

        // what the Java section's combo box does on Apply, before this one is applied
        settings.ADD_IMPORTS_ON_PASTE = CodeInsightSettings.ASK
        options.apply()

        assertEquals(CodeInsightSettings.ASK, settings.ADD_IMPORTS_ON_PASTE)
    }

    @Test
    fun `a paste setting changed here is written`() = onEdt {
        settings.ADD_IMPORTS_ON_PASTE = CodeInsightSettings.YES
        val options = ByAutoImportOptions().also { created += it }
        val component = options.createComponent()
        options.reset()

        val combo = UIUtil.findComponentOfType(component, ComboBox::class.java)!!
        combo.selectedItem = CodeInsightSettings.NO
        assertTrue(options.isModified)
        options.apply()

        assertEquals(CodeInsightSettings.NO, settings.ADD_IMPORTS_ON_PASTE)
        assertFalse(options.isModified)
    }

    @Test
    fun `unambiguous imports on the fly are off until switched on`() = onEdt {
        BasedPythonAppSettings.getInstance().addUnambiguousImportsOnTheFly = false
        val options = ByAutoImportOptions().also { created += it }
        val component = options.createComponent()
        options.reset()

        val box = UIUtil.findComponentOfType(component, JBCheckBox::class.java)!!
        assertFalse(box.isSelected)
        box.isSelected = true
        options.apply()

        assertTrue(BasedPythonAppSettings.getInstance().addUnambiguousImportsOnTheFly)
    }
}
