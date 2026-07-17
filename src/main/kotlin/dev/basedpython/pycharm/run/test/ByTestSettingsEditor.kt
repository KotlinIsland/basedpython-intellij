package dev.basedpython.pycharm.run.test

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.JComponent
import javax.swing.JPanel

class ByTestSettingsEditor : SettingsEditor<ByTestConfiguration>() {
    private val pathsField = JBTextField()
    private val workingDirField = TextFieldWithBrowseButton().apply {
        @Suppress("DEPRECATION")
        addBrowseFolderListener(
            "Working Directory",
            "Directory the by test command is invoked from",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
    }
    private val extraArgsField = JBTextField()
    private val pythonVersionField = JBTextField()
    private val envVarsComponent = EnvironmentVariablesComponent()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Test paths (space-separated):", pathsField)
        .addLabeledComponent("Working directory:", workingDirField)
        .addLabeledComponent("Extra args:", extraArgsField)
        .addLabeledComponent("Min Python version:", pythonVersionField)
        .addComponent(envVarsComponent)
        .panel

    override fun resetEditorFrom(s: ByTestConfiguration) {
        val o = s.options
        pathsField.text = o.paths
        workingDirField.text = o.workingDir
        extraArgsField.text = o.extraArgs
        pythonVersionField.text = o.pythonVersion
        envVarsComponent.envs = o.envVars
        envVarsComponent.isPassParentEnvs = o.passParentEnv
    }

    override fun applyEditorTo(s: ByTestConfiguration) {
        val o = s.options
        o.paths = pathsField.text.trim()
        o.workingDir = workingDirField.text.trim()
        o.extraArgs = extraArgsField.text.trim()
        o.pythonVersion = pythonVersionField.text.trim()
        o.envVars = LinkedHashMap(envVarsComponent.envs)
        o.passParentEnv = envVarsComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel
}
