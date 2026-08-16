package dev.basedpython.pycharm.tasks

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * The editor for a hook task.
 *
 * Most of these configurations are made by double-clicking a row and never opened. What the editor
 * is for is the two things the tree cannot do: changing which runner reads a config file (a project
 * with both `pre-commit` and `prek` installed can be pointed at either), and adding arguments to a
 * single task without editing the file every other developer shares.
 *
 * Hence the command preview. Every field here ends up as a word on a command line, and a form that
 * shows the line it is building can be checked against what the same command does in a terminal —
 * which is the first thing anybody does when a hook behaves differently inside the IDE.
 */
class ByTaskSettingsEditor : SettingsEditor<ByTaskConfiguration>() {

    private val runnerCombo = ComboBox(ByTaskRunner.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { it.display }
    }
    private val targetCombo = ComboBox(ByTaskKind.entries.toTypedArray()).apply {
        renderer = SimpleListCellRenderer.create("") { BasedPythonBundle.message("tasks.kind.${it.name.lowercase()}") }
    }
    private val taskIdField = JBTextField()
    private val stageField = JBTextField()
    private val configPathField = JBTextField()
    private val allFilesCheckBox = JBCheckBox(BasedPythonBundle.message("tasks.editor.allFiles"))
    private val extraArgsField = JBTextField()
    private val workingDirField = TextFieldWithBrowseButton().apply {
        @Suppress("DEPRECATION")
        addBrowseFolderListener(
            BasedPythonBundle.message("tasks.editor.workingDir.title"),
            BasedPythonBundle.message("tasks.editor.workingDir.description"),
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor(),
        )
    }
    private val envVarsComponent = EnvironmentVariablesComponent()
    private val commandPreview = JBLabel().apply {
        font = UIUtil.getLabelFont(UIUtil.FontSize.SMALL)
        foreground = UIUtil.getContextHelpForeground()
    }

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.runner"), runnerCombo)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.target"), targetCombo)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.task"), taskIdField)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.stage"), stageField)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.configFile"), configPathField)
        .addComponent(allFilesCheckBox)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.extraArgs"), extraArgsField)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.workingDir"), workingDirField)
        .addComponent(envVarsComponent)
        .addLabeledComponent(BasedPythonBundle.message("tasks.editor.command"), commandPreview)
        .panel

    init {
        runnerCombo.addActionListener { updatePreview() }
        targetCombo.addActionListener { updatePreview() }
        allFilesCheckBox.addActionListener { updatePreview() }
        for (field in listOf(taskIdField, stageField, extraArgsField)) {
            field.document.addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(e: DocumentEvent) = updatePreview()
            })
        }
    }

    override fun resetEditorFrom(s: ByTaskConfiguration) {
        val o = s.options
        runnerCombo.selectedItem = ByTaskRunner.fromId(o.runner)
        targetCombo.selectedItem = ByTaskKind.entries.firstOrNull { it.name == o.taskKind } ?: ByTaskKind.FILE
        taskIdField.text = o.taskId
        stageField.text = o.stage
        configPathField.text = o.configPath
        allFilesCheckBox.isSelected = o.allFiles
        extraArgsField.text = o.extraArgs
        workingDirField.text = o.workingDir
        envVarsComponent.envs = o.envVars
        envVarsComponent.isPassParentEnvs = o.passParentEnv
        updatePreview()
    }

    override fun applyEditorTo(s: ByTaskConfiguration) {
        val o = s.options
        o.runner = (runnerCombo.selectedItem as ByTaskRunner).id
        o.taskKind = (targetCombo.selectedItem as ByTaskKind).name
        o.taskId = taskIdField.text.trim()
        o.stage = stageField.text.trim()
        o.configPath = configPathField.text.trim()
        o.allFiles = allFilesCheckBox.isSelected
        o.extraArgs = extraArgsField.text.trim()
        o.workingDir = workingDirField.text.trim()
        o.envVars = LinkedHashMap(envVarsComponent.envs)
        o.passParentEnv = envVarsComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel

    /**
     * The command the current fields amount to.
     *
     * Named by the executable rather than by its resolved path: which `pre-commit` runs is decided
     * when the run starts (see [ByTaskLaunch]), and putting a path here would mean the editor
     * touching the filesystem on every keystroke to show something that can still change.
     */
    private fun updatePreview() {
        val runner = runnerCombo.selectedItem as? ByTaskRunner ?: return
        val kind = targetCombo.selectedItem as? ByTaskKind ?: return
        allFilesCheckBox.isEnabled = ByTaskCommands.supportsAllFiles(runner)
        val arguments = ByTaskCommands.arguments(
            runner = runner,
            kind = kind,
            id = taskIdField.text.trim().takeIf { it.isNotBlank() },
            stage = stageField.text.trim().takeIf { it.isNotBlank() },
            allFiles = allFilesCheckBox.isSelected && allFilesCheckBox.isEnabled,
        )
        commandPreview.text = if (arguments == null) {
            BasedPythonBundle.message("tasks.editor.command.none")
        } else {
            val extra = extraArgsField.text.trim()
            ByTaskCommands.describe(runner.binary, arguments) + if (extra.isEmpty()) "" else " $extra"
        }
    }
}
