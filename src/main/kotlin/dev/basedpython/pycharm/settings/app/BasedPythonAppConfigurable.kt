package dev.basedpython.pycharm.settings.app

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.lang.dialect.BasedPythonProjectDetector
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * Application-level Configurable for basedpython defaults. Lives at
 * Settings → Languages & Frameworks → basedpython Defaults (IDE-wide).
 *
 * Edits [BasedPythonAppSettings]; every project follows these until its own
 * project-level settings set a value (see [BasedPythonDefaults]).
 */
internal class BasedPythonAppConfigurable : Configurable {

    private val settings get() = BasedPythonAppSettings.getInstance()

    private val byPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "The by binary for projects that set none (blank = autodetect)"
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.singleFileOrDir()
                .withTitle("Select the Default by Binary")
                .withDescription("Default path to the by language server binary"),
        )
    }
    private val buffPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "The buff binary for projects that set none (blank = autodetect)"
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.singleFileOrDir()
                .withTitle("Select the Default buff Binary")
                .withDescription("Default path to the buff formatter/linter binary"),
        )
    }

    private val byEnabled = JCheckBox("Enable the by language server in projects that do not choose")
    private val buffEnabled = JCheckBox("Enable the buff (formatter/linter) server in projects that do not choose")

    private val byExtraArgs = JBTextField()
    private val buffExtraArgs = JBTextField()

    private var rootPanel: JComponent? = null

    override fun getDisplayName(): String = "basedpython Defaults"

    override fun getHelpTopic(): String = "dev.basedpython.pycharm.settings.app"

    override fun createComponent(): JComponent {
        val panel = panel {
            group("Default binaries") {
                row("Default path to by:") { cell(byPathField).align(AlignX.FILL) }
                row("Default path to buff:") { cell(buffPathField).align(AlignX.FILL) }
            }
            group("Default servers") {
                row { cell(byEnabled) }
                row { cell(buffEnabled) }
            }
            group("Default args") {
                row("Extra args for by:") { cell(byExtraArgs).align(AlignX.FILL) }
                row("Extra args for buff:") { cell(buffExtraArgs).align(AlignX.FILL) }
            }
        }
        reset()
        rootPanel = panel
        return panel
    }

    override fun isModified(): Boolean {
        val s = settings
        return byPathField.text != (s.defaultByPath ?: "") ||
            buffPathField.text != (s.defaultBuffPath ?: "") ||
            byEnabled.isSelected != s.defaultByEnabled ||
            buffEnabled.isSelected != s.defaultBuffEnabled ||
            byExtraArgs.text != s.defaultByExtraArgs ||
            buffExtraArgs.text != s.defaultBuffExtraArgs
    }

    override fun apply() {
        val changed = isModified
        val byEnabledChanged = byEnabled.isSelected != settings.defaultByEnabled
        val s = settings
        s.defaultByPath = byPathField.text.trim().ifEmpty { null }
        s.defaultBuffPath = buffPathField.text.trim().ifEmpty { null }
        s.defaultByEnabled = byEnabled.isSelected
        s.defaultBuffEnabled = buffEnabled.isSelected
        s.defaultByExtraArgs = byExtraArgs.text
        s.defaultBuffExtraArgs = buffExtraArgs.text
        // Every open project that has not chosen for itself follows these, so its servers are
        // started again the way a change on its own page restarts them.
        if (changed) {
            for (project in ProjectManager.getInstance().openProjects) {
                BasedPythonLspReloader.getInstance(project).onSettingsChanged()
            }
        }
        if (byEnabledChanged) {
            BasedPythonProjectDetector.fileTypesMayHaveChanged("basedpython default for the by server changed")
        }
    }

    override fun reset() {
        val s = settings
        byPathField.text = s.defaultByPath.orEmpty()
        buffPathField.text = s.defaultBuffPath.orEmpty()
        byEnabled.isSelected = s.defaultByEnabled
        buffEnabled.isSelected = s.defaultBuffEnabled
        byExtraArgs.text = s.defaultByExtraArgs
        buffExtraArgs.text = s.defaultBuffExtraArgs
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
