package dev.basedpython.pycharm.format

import com.intellij.application.options.CodeStyleAbstractConfigurable
import com.intellij.application.options.CodeStyleAbstractPanel
import com.intellij.application.options.TabbedLanguageCodeStylePanel
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.highlighter.EditorHighlighter
import com.intellij.openapi.fileTypes.FileType
import com.intellij.psi.codeStyle.CodeStyleConfigurable
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.codeStyle.CodeStyleSettingsProvider
import com.intellij.psi.codeStyle.CustomCodeStyleSettings
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets

// ---------------------------------------------------------------------------
// CodeStyleSettingsProvider — registers the top-level "BasedPython" entry
// under Editor → Code Style.
// ---------------------------------------------------------------------------

class BuffCodeStyleSettingsProvider : CodeStyleSettingsProvider() {

    override fun getLanguage() = BasedPythonLanguage

    override fun createConfigurable(
        settings: CodeStyleSettings,
        modelSettings: CodeStyleSettings,
    ): CodeStyleConfigurable =
        object : CodeStyleAbstractConfigurable(settings, modelSettings, configurableDisplayName) {
            override fun createPanel(settings: CodeStyleSettings): CodeStyleAbstractPanel =
                BuffCodeStyleMainPanel(currentSettings, settings)
        }

    override fun getConfigurableDisplayName(): String = "BasedPython"

    override fun createCustomSettings(settings: CodeStyleSettings): CustomCodeStyleSettings =
        BuffCodeStyleSettings(settings)
}

// ---------------------------------------------------------------------------
// LanguageCodeStyleSettingsProvider — required to show the language tab inside
// the generic Code Style dialog and to supply a preview snippet.
// ---------------------------------------------------------------------------

class BuffLanguageCodeStyleSettingsProvider : LanguageCodeStyleSettingsProvider() {

    override fun getLanguage() = BasedPythonLanguage

    override fun getCodeSample(settingsType: SettingsType): String = CODE_SAMPLE

    override fun customizeDefaults(
        commonSettings: com.intellij.psi.codeStyle.CommonCodeStyleSettings,
        indentOptions: com.intellij.psi.codeStyle.CommonCodeStyleSettings.IndentOptions,
    ) {
        commonSettings.RIGHT_MARGIN = BuffCodeStyleSettings.DEFAULT_LINE_LENGTH
        indentOptions.INDENT_SIZE = 4
        indentOptions.TAB_SIZE = 4
        indentOptions.USE_TAB_CHARACTER = false
    }

    private companion object {
        val CODE_SAMPLE = """
            |from typing import Optional
            |
            |
            |def greet(name: str, greeting: Optional[str] = None) -> str:
            |    msg = greeting or "Hello"
            |    return f"{msg}, {name}!"
            |
            |
            |class Greeter:
            |    def __init__(self, name: str) -> None:
            |        self.name = name
            |
            |    def greet(self) -> str:
            |        return greet(self.name)
        """.trimMargin()
    }
}

// ---------------------------------------------------------------------------
// Main tabbed panel — wraps the buff-specific settings tab.
// ---------------------------------------------------------------------------

private class BuffCodeStyleMainPanel(
    currentSettings: CodeStyleSettings,
    settings: CodeStyleSettings,
) : TabbedLanguageCodeStylePanel(BasedPythonLanguage, currentSettings, settings) {

    override fun initTabs(settings: CodeStyleSettings) {
        addTab(BuffOptionsTab(settings))
    }
}

// ---------------------------------------------------------------------------
// Buff-specific options tab: line length + quote style.
// ---------------------------------------------------------------------------

private class BuffOptionsTab(settings: CodeStyleSettings) :
    CodeStyleAbstractPanel(BasedPythonLanguage, null, settings) {

    private val lineLengthSpinner = JSpinner(SpinnerNumberModel(88, 40, 320, 1))
    private val quoteStyleCombo = JComboBox(BuffCodeStyleSettings.QUOTE_OPTIONS)

    private val rootPanel: JPanel = buildPanel()

    private fun buildPanel(): JPanel {
        val p = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = Insets(4, 8, 4, 8)
        }

        gbc.gridy = 0; gbc.gridx = 0; gbc.weightx = 0.0
        p.add(JLabel("Line length:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        p.add(lineLengthSpinner, gbc)

        gbc.gridy = 1; gbc.gridx = 0; gbc.weightx = 0.0
        p.add(JLabel("Quote style:"), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        p.add(quoteStyleCombo, gbc)

        // Push widgets to the top.
        gbc.gridy = 2; gbc.gridx = 0; gbc.gridwidth = 2
        gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        p.add(JPanel(), gbc)

        return p
    }

    // --- CodeStyleAbstractPanel contract ------------------------------------

    override fun getTabTitle(): String = "buff"

    override fun getPanel(): JPanel = rootPanel

    override fun resetImpl(settings: CodeStyleSettings) {
        val custom = settings.getCustomSettings(BuffCodeStyleSettings::class.java)
        lineLengthSpinner.value = custom.lineLength
        quoteStyleCombo.selectedIndex = custom.quoteStyle
    }

    override fun apply(settings: CodeStyleSettings) {
        val custom = settings.getCustomSettings(BuffCodeStyleSettings::class.java)
        custom.lineLength = lineLengthSpinner.value as Int
        custom.quoteStyle = quoteStyleCombo.selectedIndex
    }

    override fun isModified(settings: CodeStyleSettings): Boolean {
        val custom = settings.getCustomSettings(BuffCodeStyleSettings::class.java)
        return custom.lineLength != lineLengthSpinner.value as Int ||
            custom.quoteStyle != quoteStyleCombo.selectedIndex
    }

    // --- Preview panel (not used — no PSI for BasedPython yet) -------------

    override fun getPreviewText(): String? = null

    override fun getRightMargin(): Int = BuffCodeStyleSettings.DEFAULT_LINE_LENGTH

    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null

    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE
}
