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
import com.intellij.psi.codeStyle.LanguageCodeStyleSettingsProvider
import dev.basedpython.pycharm.lang.BasedPythonFileType
import dev.basedpython.pycharm.lang.BasedPythonLanguage
import javax.swing.JPanel
import java.awt.BorderLayout
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import dev.basedpython.pycharm.util.BasedPythonBundle

// ---------------------------------------------------------------------------
// CodeStyleSettingsProvider — registers the top-level "basedpython" entry
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

    override fun getConfigurableDisplayName(): String = "basedpython"
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
        commonSettings.RIGHT_MARGIN = BUFF_DEFAULT_LINE_LENGTH
        // `# total = 1`, as Python writes it; see BasedPythonCommenter for why not a `"# "` prefix.
        commonSettings.LINE_COMMENT_ADD_SPACE = true
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

/** buff's own default `line-length`: the right margin until the project's configuration is read. */
private const val BUFF_DEFAULT_LINE_LENGTH = 88

// ---------------------------------------------------------------------------
// Main tabbed panel — wraps the buff tab.
// ---------------------------------------------------------------------------

private class BuffCodeStyleMainPanel(
    currentSettings: CodeStyleSettings,
    settings: CodeStyleSettings,
) : TabbedLanguageCodeStylePanel(BasedPythonLanguage, currentSettings, settings) {

    override fun initTabs(settings: CodeStyleSettings) {
        addTab(BuffOptionsTab(settings))
    }
}

/**
 * The buff tab: where buff's formatting options are, and nothing to edit here.
 *
 * It used to offer a line length and a quote style that nothing in the plugin read, so Reformat
 * went on wrapping at buff's 88 with its double quotes: buff, like ruff, reads them from the
 * project's configuration files, which is what `buff format` applies on the command line and in CI.
 * Sending the IDE's values instead would be wrong for buff: its server lets editor settings
 * override the files (`configurationPreference` defaults to `editorFirst`), so the IDE would format
 * differently from everything else that runs buff. Showing the values buff resolved for a file is
 * what belongs here, and waits on buff reporting them.
 */
private class BuffOptionsTab(settings: CodeStyleSettings) :
    CodeStyleAbstractPanel(BasedPythonLanguage, null, settings) {

    private val rootPanel: JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(8)
        add(JBLabel(BasedPythonBundle.message("codeStyle.buff.fromConfiguration")).apply { setAllowAutoWrapping(true) }, BorderLayout.NORTH)
    }

    override fun getTabTitle(): String = "buff"

    override fun getPanel(): JPanel = rootPanel

    override fun resetImpl(settings: CodeStyleSettings) = Unit

    override fun apply(settings: CodeStyleSettings) = Unit

    override fun isModified(settings: CodeStyleSettings): Boolean = false

    // --- Preview panel (not used — no PSI for basedpython yet) -------------

    override fun getPreviewText(): String? = null

    override fun getRightMargin(): Int = BUFF_DEFAULT_LINE_LENGTH

    override fun createHighlighter(scheme: EditorColorsScheme): EditorHighlighter? = null

    override fun getFileType(): FileType = BasedPythonFileType.INSTANCE
}
