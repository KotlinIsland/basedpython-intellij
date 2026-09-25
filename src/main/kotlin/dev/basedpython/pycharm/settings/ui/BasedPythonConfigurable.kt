package dev.basedpython.pycharm.settings.ui

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.impl.InlayHintsPassFactoryInternal
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import dev.basedpython.pycharm.lang.dialect.PyFileHandling
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import dev.basedpython.pycharm.lsp.BasedPythonBinaries
import dev.basedpython.pycharm.lsp.diagnostics.ByProjectDiagnostics
import dev.basedpython.pycharm.lsp.inlay.ByHintKind
import dev.basedpython.pycharm.lsp.inlay.ByHintMode
import dev.basedpython.pycharm.lsp.inlay.ByPushKey
import dev.basedpython.pycharm.lsp.reload.BasedPythonLspReloader
import dev.basedpython.pycharm.debug.bpd.ByDebugBackend
import dev.basedpython.pycharm.debug.dfa.ByDataFlowSession
import dev.basedpython.pycharm.debug.recompose.ByRecompositionSession
import dev.basedpython.pycharm.settings.BasedPythonSettings
import dev.basedpython.pycharm.settings.BasedPythonSettingsEffects
import dev.basedpython.pycharm.util.BasedPythonBundle
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
            project,
            FileChooserDescriptorFactory.singleFileOrDir()
                .withTitle("Select the by Binary")
                .withDescription("Path to the by language server binary"),
        )
    }
    private val buffPathField = TextFieldWithBrowseButton().apply {
        textField.toolTipText = "Autodetect from .venv/"
        addBrowseFolderListener(
            project,
            FileChooserDescriptorFactory.singleFileOrDir()
                .withTitle("Select the buff Binary")
                .withDescription("Path to the buff formatter/linter binary"),
        )
    }

    private val byEnabled = JCheckBox("Enable the by language server")
    private val buffEnabled = JCheckBox("Enable the buff (formatter/linter) server")

    private val byExtraArgs = JBTextField()
    private val buffExtraArgs = JBTextField()

    // No on-save checkboxes here: "Apply all basedpython fixes" lives on the platform's own
    // Settings | Tools | Actions on Save page, beside Reformat code and Optimize imports. A second
    // pair of checkboxes for the same settings meant whichever page was applied last won, so the
    // one ticked on the other page was quietly turned back off.

    /**
     * One mode per kind of hint `by` sends, in [ByHintKind]'s order: each kind
     * can be off, always on, or shown only while the push key is held.
     */
    private val inlayModeCombos: Map<ByHintKind, ComboBox<ByHintMode>> =
        ByHintKind.entries.associateWith { modeCombo() }

    private val inlayPushKeyCombo = ComboBox(ByPushKey.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { it.display }
    }

    private val indexGeneratedPython = JCheckBox(
        "Index generated .py in build/ (enables native Python support — requires a Python plugin)",
    )

    /** Renders a [PyFileHandling] by its user-facing text while the model holds the enum. */
    private val pyFileHandlingCombo = ComboBox(PyFileHandling.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") { it.display }
    }

    private val debugBackendCombo = ComboBox(ByDebugBackend.entries.toTypedArray()).apply {
        renderer = textListCellRenderer("") {
            when (it) {
                ByDebugBackend.BPD -> "bpd (recommended)"
                ByDebugBackend.DEBUGPY -> "debugpy"
            }
        }
    }

    private val debuggerDataFlow = JCheckBox("Show what a stopped program settles about the code below it")
    private val debuggerRecompositions = JCheckBox(BasedPythonBundle.message("settings.debug.recompositions"))

    // Per-server capability toggles (§142)
    private val byCompletion = JCheckBox("Completion")
    private val byGoToDefinition = JCheckBox("Go to definition / type definition")
    private val byFindReferences = JCheckBox("Find references")
    private val byRename = JCheckBox("Rename")
    private val bySemanticTokens = JCheckBox("Semantic tokens (coloring)")
    private val byCodeLens = JCheckBox("Code lens")
    private val byDocumentHighlight = JCheckBox("Highlight usages")
    private val bySignatureHelp = JCheckBox("Signature help")
    private val byLanguageInjection = JCheckBox("Language injection (html, sql, … inside strings)")
    private val byProjectDiagnostics = JCheckBox("Problems in every file of the project, open or not")
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
            group("Inlay hints") {
                for ((kind, combo) in inlayModeCombos) {
                    val row = row("${kind.display}:") { cell(combo) }
                    if (kind == ByHintKind.OTHER) {
                        row.comment(
                            "Hints of a kind this plugin does not know yet, and every hint from a by " +
                                "that does not say which kind each hint is. Never still works per kind.",
                        )
                    }
                }
                row("Push key:") { cell(inlayPushKeyCombo) }
                    .comment("Hold this key to see the hints set to show while it is held.")
            }
            group("Python interop") {
                row("Treat .py as basedpython:") { cell(pyFileHandlingCombo) }
                    .comment(
                        "Only affects who owns the .py file type. The by server still checks .py " +
                            "files in a basedpython project either way.",
                    )
                row { cell(indexGeneratedPython) }
            }
            group("Debugger") {
                row("Debugger:") { cell(debugBackendCombo) }
                    .comment(
                        "bpd is PEP 669 native and is the only backend that can seed the " +
                            "data-flow analysis below. debugpy needs no extra binary.",
                    )
                row { cell(debuggerDataFlow) }
                    .comment(
                        "While stopped, draw which branches will be taken. Needs the bpd backend.",
                    )
                row { cell(debuggerRecompositions) }
                    .comment(BasedPythonBundle.message("settings.debug.recompositions.comment"))
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
                row { cell(byLanguageInjection) }
                row { cell(byProjectDiagnostics) }
                    .comment(
                        "Listed in Problems | Project Errors. by checks every file rather than the " +
                            "open ones, which takes several times the memory.",
                    )
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

    /** Renders a [ByHintMode] by its user-facing text while the model holds the enum. */
    private fun modeCombo(): ComboBox<ByHintMode> =
        ComboBox(ByHintMode.entries.toTypedArray()).apply {
            renderer = textListCellRenderer("") { it.display }
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

    /**
     * Shows the resolved command *and* which source produced it. With several sources feeding
     * auto-detection (.venv, interpreter, uv, PATH), "what did it actually pick, and why" is the
     * question this label exists to answer.
     */
    private fun updateDetectedLabel() {
        val launch = BasedPythonBinaries.launchBy(project)
        detectedVenvLabel.text = if (launch == null) {
            "Detected by: (none — install by or set path above)"
        } else {
            "Detected by: ${launch.describe()}  [${launch.sourceLabel}]"
        }
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
            inlayModified() ||
            indexGeneratedPython.isSelected != s.indexGeneratedPython ||
            pyFileHandlingCombo.selectedItem != s.pyFileHandling ||
            debugBackendCombo.selectedItem != s.debugBackend ||
            debuggerDataFlow.isSelected != s.debuggerDataFlow ||
            byProjectDiagnostics.isSelected != s.byProjectDiagnostics ||
            debuggerRecompositions.isSelected != s.debuggerRecompositions ||
            byCompletion.isSelected != s.byCompletion ||
            byGoToDefinition.isSelected != s.byGoToDefinition ||
            byFindReferences.isSelected != s.byFindReferences ||
            byRename.isSelected != s.byRename ||
            bySemanticTokens.isSelected != s.bySemanticTokens ||
            byCodeLens.isSelected != s.byCodeLens ||
            byDocumentHighlight.isSelected != s.byDocumentHighlight ||
            bySignatureHelp.isSelected != s.bySignatureHelp ||
            byLanguageInjection.isSelected != s.byLanguageInjection ||
            buffFormatting.isSelected != s.buffFormatting ||
            buffCodeActions.isSelected != s.buffCodeActions ||
            buffHover.isSelected != s.buffHover
    }

    /** Split out of [isModified] because [apply] needs the same answer, to know whether to redraw. */
    private fun inlayModified(): Boolean {
        val s = settings
        return inlayModeCombos.any { (kind, combo) -> combo.selectedItem != s.inlayMode(kind) } ||
            inlayPushKeyCombo.selectedItem != s.inlayPushKey
    }

    override fun apply() {
        val s = settings
        val before = BasedPythonSettingsEffects.snapshot(s)
        s.byPath = byPathField.text.trim().ifEmpty { null }
        s.buffPath = buffPathField.text.trim().ifEmpty { null }
        // Written only when changed, so a project that never touched the box keeps following the
        // IDE-wide default instead of freezing the value it happened to show.
        if (byEnabled.isSelected != s.byEnabled) s.byEnabled = byEnabled.isSelected
        if (buffEnabled.isSelected != s.buffEnabled) s.buffEnabled = buffEnabled.isSelected
        s.byExtraArgs = byExtraArgs.text
        s.buffExtraArgs = buffExtraArgs.text
        val inlayChanged = inlayModified()
        for ((kind, combo) in inlayModeCombos) {
            s.setInlayMode(kind, combo.selectedItem as? ByHintMode ?: ByHintMode.ALWAYS)
        }
        s.inlayPushKey = inlayPushKeyCombo.selectedItem as? ByPushKey ?: ByPushKey.CTRL_ALT

        s.indexGeneratedPython = indexGeneratedPython.isSelected
        s.pyFileHandling = pyFileHandlingCombo.selectedItem as? PyFileHandling ?: PyFileHandling.AUTO
        s.debugBackend = debugBackendCombo.selectedItem as? ByDebugBackend ?: ByDebugBackend.BPD
        val dataFlowChanged = debuggerDataFlow.isSelected != s.debuggerDataFlow
        s.debuggerDataFlow = debuggerDataFlow.isSelected
        val recompositionsChanged = debuggerRecompositions.isSelected != s.debuggerRecompositions
        s.debuggerRecompositions = debuggerRecompositions.isSelected
        // Off must take what a stopped session drew down now: the pass that would have removed it
        // no longer runs once the factory declines, so each service removes its marks itself
        val projectDiagnosticsChanged = byProjectDiagnostics.isSelected != s.byProjectDiagnostics
        s.byProjectDiagnostics = byProjectDiagnostics.isSelected
        if (projectDiagnosticsChanged) ByProjectDiagnostics.getInstance(project).settingChanged()
        if (dataFlowChanged) ByDataFlowSession.getInstance(project).settingChanged()
        if (recompositionsChanged) ByRecompositionSession.getInstance(project).settingChanged()
        BasedPythonSettingsEffects.announce(project, before)

        s.byCompletion = byCompletion.isSelected
        s.byGoToDefinition = byGoToDefinition.isSelected
        s.byFindReferences = byFindReferences.isSelected
        s.byRename = byRename.isSelected
        s.bySemanticTokens = bySemanticTokens.isSelected
        s.byCodeLens = byCodeLens.isSelected
        s.byDocumentHighlight = byDocumentHighlight.isSelected
        s.bySignatureHelp = bySignatureHelp.isSelected
        s.byLanguageInjection = byLanguageInjection.isSelected
        s.buffFormatting = buffFormatting.isSelected
        s.buffCodeActions = buffCodeActions.isSelected
        s.buffHover = buffHover.isSelected

        if (inlayChanged) redrawInlayHints()
        BasedPythonLspReloader.getInstance(project).onSettingsChanged()
        updateDetectedLabel()
    }

    /**
     * Re-collect the inlay hints of every open editor, so a changed mode is visible on Apply.
     *
     * A plain daemon restart is not enough: the platform's inlay pass keeps the PSI modification
     * stamp it last ran against and declines to build a pass at all when the file has not changed
     * since, which is precisely the case here — the file is the same, the settings are not.
     * [InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass] clears that stamp; without it a
     * changed mode would take effect on the next keystroke instead.
     */
    private fun redrawInlayHints() {
        InlayHintsPassFactoryInternal.forceHintsUpdateOnNextPass()
        DaemonCodeAnalyzer.getInstance(project).restart("basedpython inlay hint settings applied")
    }

    override fun reset() {
        val s = settings
        byPathField.text = s.byPath.orEmpty()
        buffPathField.text = s.buffPath.orEmpty()
        byEnabled.isSelected = s.byEnabled
        buffEnabled.isSelected = s.buffEnabled
        byExtraArgs.text = s.byExtraArgs
        buffExtraArgs.text = s.buffExtraArgs
        for ((kind, combo) in inlayModeCombos) combo.selectedItem = s.inlayMode(kind)
        inlayPushKeyCombo.selectedItem = s.inlayPushKey
        indexGeneratedPython.isSelected = s.indexGeneratedPython
        pyFileHandlingCombo.selectedItem = s.pyFileHandling
        debugBackendCombo.selectedItem = s.debugBackend
        debuggerDataFlow.isSelected = s.debuggerDataFlow
        byProjectDiagnostics.isSelected = s.byProjectDiagnostics
        debuggerRecompositions.isSelected = s.debuggerRecompositions
        byCompletion.isSelected = s.byCompletion
        byGoToDefinition.isSelected = s.byGoToDefinition
        byFindReferences.isSelected = s.byFindReferences
        byRename.isSelected = s.byRename
        bySemanticTokens.isSelected = s.bySemanticTokens
        byCodeLens.isSelected = s.byCodeLens
        byDocumentHighlight.isSelected = s.byDocumentHighlight
        bySignatureHelp.isSelected = s.bySignatureHelp
        byLanguageInjection.isSelected = s.byLanguageInjection
        buffFormatting.isSelected = s.buffFormatting
        buffCodeActions.isSelected = s.buffCodeActions
        buffHover.isSelected = s.buffHover
        updateDetectedLabel()
    }

    override fun disposeUIResources() {
        rootPanel = null
    }

    private class SimpleDocListener(val onChange: () -> Unit) : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent) { onChange() }
        override fun removeUpdate(e: javax.swing.event.DocumentEvent) { onChange() }
        override fun changedUpdate(e: javax.swing.event.DocumentEvent) { onChange() }
    }
}
