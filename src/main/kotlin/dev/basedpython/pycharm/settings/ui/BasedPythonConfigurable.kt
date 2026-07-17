package dev.basedpython.pycharm.settings.ui

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.settings.BasedPythonSettings
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel

/**
 * Project-level Configurable for basedpython. Lives at
 * Settings → Languages & Frameworks → basedpython.
 */
internal class BasedPythonConfigurable(private val project: Project) : Configurable {

    private val settings get() = BasedPythonSettings.getInstance(project)

    override fun getHelpTopic(): String = "dev.basedpython.pycharm.settings"

    // Widgets
    private val byPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "Autodetect from .venv/"
        addBrowseFolderListener(
            "Select the by Binary",
            "Path to the by language server binary",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )
    }
    private val buffPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "Autodetect from .venv/"
        addBrowseFolderListener(
            "Select the buff Binary",
            "Path to the buff formatter/linter binary",
            project,
            FileChooserDescriptorFactory.createSingleFileDescriptor(),
        )
    }

    private val byEnabled = JCheckBox("Enable the by language server")
    private val buffEnabled = JCheckBox("Enable the buff (formatter/linter) server")

    private val byExtraArgs = JBTextField()
    private val buffExtraArgs = JBTextField()

    private val pythonVersionCombo = ComboBox(arrayOf("3.10", "3.11", "3.12", "3.13"))

    private val formatOnSave = JCheckBox("Reformat with buff on save")
    private val inlayParameterHints = JCheckBox("Parameter name hints")
    private val inlayTypeHints = JCheckBox("Variable type hints")
    private val inlayReturnHints = JCheckBox("Return type hints")
    private val lspTraceCombo = ComboBox(arrayOf("off", "messages", "verbose"))

    private val indexGeneratedPython = JCheckBox(
        "Index generated .py in out/ (enables native Python support — requires a Python plugin)",
    )

    // Per-server capability toggles (§142)
    private val byCompletion = JCheckBox("Completion")
    private val byGoToDefinition = JCheckBox("Go to definition / type definition")
    private val byFindReferences = JCheckBox("Find references")
    private val byRename = JCheckBox("Rename")
    private val bySemanticTokens = JCheckBox("Semantic tokens (coloring)")
    private val byCodeLens = JCheckBox("Code lens")
    private val byDocumentHighlight = JCheckBox("Highlight usages")
    private val bySignatureHelp = JCheckBox("Signature help")
    private val buffFormatting = JCheckBox("Formatting")
    private val buffCodeActions = JCheckBox("Code actions (lint fixes)")
    private val buffHover = JCheckBox("Hover")

    private val detectedVenvLabel = JBLabel("Detected venv binary: …")

    private var rootPanel: JComponent? = null

    override fun getDisplayName(): String = "basedpython"

    override fun createComponent(): JComponent {
        val testByBtn = JButton("Test").apply { addActionListener { runVersionCheck(byPathField.text, "by") } }
        val testBuffBtn = JButton("Test").apply { addActionListener { runVersionCheck(buffPathField.text, "buff") } }

        val byRow = rowWithButton(byPathField, testByBtn)
        val buffRow = rowWithButton(buffPathField, testBuffBtn)

        val panel = panel {
            group("Binaries") {
                row("Path to by:") { cell(byRow).align(AlignX.FILL) }
                row("Path to buff:") { cell(buffRow).align(AlignX.FILL) }
                row { cell(detectedVenvLabel) }
            }
            group("Servers") {
                row { cell(byEnabled) }
                row { cell(buffEnabled) }
            }
            group("Args") {
                row("Extra args for by:") { cell(byExtraArgs).align(AlignX.FILL) }
                row("Extra args for buff:") { cell(buffExtraArgs).align(AlignX.FILL) }
            }
            group("Target") {
                row("Min Python version:") { cell(pythonVersionCombo) }
            }
            group("Formatting") {
                row { cell(formatOnSave) }
            }
            group("Inlay hints") {
                row { cell(inlayParameterHints) }
                row { cell(inlayTypeHints) }
                row { cell(inlayReturnHints) }
            }
            group("Diagnostics") {
                row("LSP trace level:") { cell(lspTraceCombo) }
            }
            group("Python interop") {
                row { cell(indexGeneratedPython) }
            }
            group("by server capabilities") {
                row { cell(byCompletion) }
                row { cell(byGoToDefinition) }
                row { cell(byFindReferences) }
                row { cell(byRename) }
                row { cell(bySemanticTokens) }
                row { cell(byCodeLens) }
                row { cell(byDocumentHighlight) }
                row { cell(bySignatureHelp) }
            }
            group("buff server capabilities") {
                row { cell(buffFormatting) }
                row { cell(buffCodeActions) }
                row { cell(buffHover) }
            }
        }

        // Wire live detection
        byPathField.textField.document.addDocumentListener(SimpleDocListener { updateDetectedLabel() })
        reset()
        rootPanel = panel
        return panel
    }

    private fun rowWithButton(field: JComponent, button: JButton): JComponent {
        val p = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            gridx = 0; gridy = 0; weightx = 1.0; fill = GridBagConstraints.HORIZONTAL
        }
        p.add(field, c)
        val c2 = GridBagConstraints().apply {
            gridx = 1; gridy = 0; weightx = 0.0; fill = GridBagConstraints.NONE
            insets = java.awt.Insets(0, 6, 0, 0)
        }
        p.add(button, c2)
        return p
    }

    private fun updateDetectedLabel() {
        val resolved = BasedPythonBinaries.resolveBy(project)?.toString() ?: "(none — install by or set path above)"
        detectedVenvLabel.text = "Detected venv binary: $resolved"
    }

    private fun runVersionCheck(path: String, name: String) {
        val trimmed = path.trim().ifEmpty {
            JOptionPane.showMessageDialog(rootPanel, "No path set for $name.", "Test $name", JOptionPane.WARNING_MESSAGE)
            return
        }
        try {
            val proc = ProcessBuilder(trimmed, "version").redirectErrorStream(true).start()
            val finished = proc.waitFor(5, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                JOptionPane.showMessageDialog(rootPanel, "Timed out running $trimmed version.", "Test $name", JOptionPane.ERROR_MESSAGE)
                return
            }
            val out = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }.trim()
            val icon = if (proc.exitValue() == 0) JOptionPane.INFORMATION_MESSAGE else JOptionPane.ERROR_MESSAGE
            JOptionPane.showMessageDialog(rootPanel, out.ifEmpty { "(no output)" }, "Test $name (exit ${proc.exitValue()})", icon)
        } catch (t: Throwable) {
            JOptionPane.showMessageDialog(rootPanel, "Failed: ${t.message}", "Test $name", JOptionPane.ERROR_MESSAGE)
        }
    }

    override fun isModified(): Boolean {
        val s = settings
        return byPathField.text != (s.byPath ?: "") ||
            buffPathField.text != (s.buffPath ?: "") ||
            byEnabled.isSelected != s.byEnabled ||
            buffEnabled.isSelected != s.buffEnabled ||
            byExtraArgs.text != s.byExtraArgs ||
            buffExtraArgs.text != s.buffExtraArgs ||
            (pythonVersionCombo.selectedItem as? String ?: "3.10") != s.pythonVersion ||
            formatOnSave.isSelected != s.formatOnSave ||
            inlayParameterHints.isSelected != s.inlayParameterHints ||
            inlayTypeHints.isSelected != s.inlayTypeHints ||
            inlayReturnHints.isSelected != s.inlayReturnHints ||
            (lspTraceCombo.selectedItem as? String ?: "off") != s.lspTraceLevel ||
            indexGeneratedPython.isSelected != s.indexGeneratedPython ||
            byCompletion.isSelected != s.byCompletion ||
            byGoToDefinition.isSelected != s.byGoToDefinition ||
            byFindReferences.isSelected != s.byFindReferences ||
            byRename.isSelected != s.byRename ||
            bySemanticTokens.isSelected != s.bySemanticTokens ||
            byCodeLens.isSelected != s.byCodeLens ||
            byDocumentHighlight.isSelected != s.byDocumentHighlight ||
            bySignatureHelp.isSelected != s.bySignatureHelp ||
            buffFormatting.isSelected != s.buffFormatting ||
            buffCodeActions.isSelected != s.buffCodeActions ||
            buffHover.isSelected != s.buffHover
    }

    override fun apply() {
        val s = settings
        s.byPath = byPathField.text.trim().ifEmpty { null }
        s.buffPath = buffPathField.text.trim().ifEmpty { null }
        s.byEnabled = byEnabled.isSelected
        s.buffEnabled = buffEnabled.isSelected
        s.byExtraArgs = byExtraArgs.text
        s.buffExtraArgs = buffExtraArgs.text
        s.pythonVersion = pythonVersionCombo.selectedItem as? String ?: "3.10"
        s.formatOnSave = formatOnSave.isSelected
        s.inlayParameterHints = inlayParameterHints.isSelected
        s.inlayTypeHints = inlayTypeHints.isSelected
        s.inlayReturnHints = inlayReturnHints.isSelected
        s.lspTraceLevel = lspTraceCombo.selectedItem as? String ?: "off"

        val indexChanged = indexGeneratedPython.isSelected != s.indexGeneratedPython
        s.indexGeneratedPython = indexGeneratedPython.isSelected
        if (indexChanged) fireRootsRescan()

        s.byCompletion = byCompletion.isSelected
        s.byGoToDefinition = byGoToDefinition.isSelected
        s.byFindReferences = byFindReferences.isSelected
        s.byRename = byRename.isSelected
        s.bySemanticTokens = bySemanticTokens.isSelected
        s.byCodeLens = byCodeLens.isSelected
        s.byDocumentHighlight = byDocumentHighlight.isSelected
        s.bySignatureHelp = bySignatureHelp.isSelected
        s.buffFormatting = buffFormatting.isSelected
        s.buffCodeActions = buffCodeActions.isSelected
        s.buffHover = buffHover.isSelected

        BasedPythonLspReloader.getInstance(project).onSettingsChanged()
        updateDetectedLabel()
    }

    /**
     * Re-evaluate directory-index exclusions so toggling [indexGeneratedPython]
     * immediately includes/excludes the generated `out/` directory.
     */
    private fun fireRootsRescan() {
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            com.intellij.openapi.application.WriteAction.run<RuntimeException> {
                com.intellij.openapi.roots.ex.ProjectRootManagerEx
                    .getInstanceEx(project)
                    .makeRootsChange(
                        com.intellij.openapi.util.EmptyRunnable.getInstance(),
                        com.intellij.openapi.project.RootsChangeRescanningInfo.TOTAL_RESCAN,
                    )
            }
        }
    }

    override fun reset() {
        val s = settings
        byPathField.text = s.byPath.orEmpty()
        buffPathField.text = s.buffPath.orEmpty()
        byEnabled.isSelected = s.byEnabled
        buffEnabled.isSelected = s.buffEnabled
        byExtraArgs.text = s.byExtraArgs
        buffExtraArgs.text = s.buffExtraArgs
        pythonVersionCombo.selectedItem = s.pythonVersion
        formatOnSave.isSelected = s.formatOnSave
        inlayParameterHints.isSelected = s.inlayParameterHints
        inlayTypeHints.isSelected = s.inlayTypeHints
        inlayReturnHints.isSelected = s.inlayReturnHints
        lspTraceCombo.selectedItem = s.lspTraceLevel
        indexGeneratedPython.isSelected = s.indexGeneratedPython
        byCompletion.isSelected = s.byCompletion
        byGoToDefinition.isSelected = s.byGoToDefinition
        byFindReferences.isSelected = s.byFindReferences
        byRename.isSelected = s.byRename
        bySemanticTokens.isSelected = s.bySemanticTokens
        byCodeLens.isSelected = s.byCodeLens
        byDocumentHighlight.isSelected = s.byDocumentHighlight
        bySignatureHelp.isSelected = s.bySignatureHelp
        buffFormatting.isSelected = s.buffFormatting
        buffCodeActions.isSelected = s.buffCodeActions
        buffHover.isSelected = s.buffHover
        updateDetectedLabel()
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    /**
     * Trigger LSP restart. Stream B owns LspServerManager wiring; we reflectively
     * invoke its stopAndRestartIfNeeded if available so we don't hard-depend on
     * a class that may shift names during integration.
     */
    private class SimpleDocListener(val onChange: () -> Unit) : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) { onChange() }
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) { onChange() }
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) { onChange() }
    }
}
