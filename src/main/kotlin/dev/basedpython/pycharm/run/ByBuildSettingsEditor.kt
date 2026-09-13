package dev.basedpython.pycharm.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ByBuildSettingsEditor(private val project: Project) : SettingsEditor<ByBuildConfiguration>() {
    private val environmentCombo = ByEnvironmentComboBox()
    private val workingDirField = TextFieldWithBrowseButton().apply {
        addBrowseFolderListener(
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
                .withTitle("Working Directory")
                .withDescription("Directory the by build command is invoked from"),
        )
    }
    private val extraArgsField = JBTextField()
    private val pythonVersionField = JBTextField()
    private val envVarsComponent = EnvironmentVariablesComponent(project)

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Environment:", environmentCombo)
        .addLabeledComponent("Working directory:", workingDirField)
        .addLabeledComponent("Extra args:", extraArgsField)
        .addLabeledComponent("Min Python version:", pythonVersionField)
        .addComponent(envVarsComponent)
        .panel

    override fun resetEditorFrom(s: ByBuildConfiguration) {
        val o = s.options
        environmentCombo.kind = o.environmentKind
        workingDirField.text = o.workingDir
        extraArgsField.text = o.extraArgs
        pythonVersionField.text = o.pythonVersion
        envVarsComponent.envs = o.envVars
        envVarsComponent.isPassParentEnvs = o.passParentEnv
    }

    override fun applyEditorTo(s: ByBuildConfiguration) {
        val o = s.options
        o.environmentKind = environmentCombo.kind
        o.workingDir = workingDirField.text.trim()
        o.extraArgs = extraArgsField.text.trim()
        o.pythonVersion = pythonVersionField.text.trim()
        o.envVars = LinkedHashMap(envVarsComponent.envs)
        o.passParentEnv = envVarsComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel
}
