package dev.basedpython.pycharm.settings.app

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * Application-level Configurable for basedpython defaults. Lives at
 * Settings → Languages & Frameworks → basedpython Defaults (IDE-wide).
 *
 * Edits [BasedPythonAppSettings]; new projects inherit these unless their
 * project-level settings override them (see [BasedPythonDefaults]).
 */
internal class BasedPythonAppConfigurable : Configurable {

    private val settings get() = BasedPythonAppSettings.getInstance()

    private val byPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "Default by binary for new projects (blank = autodetect)"
        addBrowseFolderListener(
            "Select the Default by Binary",
            "Default path to the by language server binary",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )
    }
    private val buffPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "Default buff binary for new projects (blank = autodetect)"
        addBrowseFolderListener(
            "Select the Default buff Binary",
            "Default path to the buff formatter/linter binary",
            null,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )
    }

    private val byEnabled = JCheckBox("Enable the by language server by default")
    private val buffEnabled = JCheckBox("Enable the buff (formatter/linter) server by default")

    private val byExtraArgs = JBTextField()
    private val buffExtraArgs = JBTextField()

    private val pythonVersionCombo = ComboBox(arrayOf("3.10", "3.11", "3.12", "3.13"))
    private val lspTraceCombo = ComboBox(arrayOf("off", "messages", "verbose"))

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
            group("Default target") {
                row("Min Python version:") { cell(pythonVersionCombo) }
            }
            group("Default diagnostics") {
                row("LSP trace level:") { cell(lspTraceCombo) }
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
            buffExtraArgs.text != s.defaultBuffExtraArgs ||
            (pythonVersionCombo.selectedItem as? String ?: "3.10") != s.defaultPythonVersion ||
            (lspTraceCombo.selectedItem as? String ?: "off") != s.defaultLspTraceLevel
    }

    override fun apply() {
        val s = settings
        s.defaultByPath = byPathField.text.trim().ifEmpty { null }
        s.defaultBuffPath = buffPathField.text.trim().ifEmpty { null }
        s.defaultByEnabled = byEnabled.isSelected
        s.defaultBuffEnabled = buffEnabled.isSelected
        s.defaultByExtraArgs = byExtraArgs.text
        s.defaultBuffExtraArgs = buffExtraArgs.text
        s.defaultPythonVersion = pythonVersionCombo.selectedItem as? String ?: "3.10"
        s.defaultLspTraceLevel = lspTraceCombo.selectedItem as? String ?: "off"
    }

    override fun reset() {
        val s = settings
        byPathField.text = s.defaultByPath.orEmpty()
        buffPathField.text = s.defaultBuffPath.orEmpty()
        byEnabled.isSelected = s.defaultByEnabled
        buffEnabled.isSelected = s.defaultBuffEnabled
        byExtraArgs.text = s.defaultByExtraArgs
        buffExtraArgs.text = s.defaultBuffExtraArgs
        pythonVersionCombo.selectedItem = s.defaultPythonVersion
        lspTraceCombo.selectedItem = s.defaultLspTraceLevel
    }

    override fun disposeUIResources() {
        rootPanel = null
    }
}
