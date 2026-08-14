package dev.basedpython.pycharm.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import dev.basedpython.pycharm.run.main.ByMainArgumentsDialog
import dev.basedpython.pycharm.run.main.ByMainModules
import javax.swing.JComponent
import javax.swing.JPanel

class ByRunSettingsEditor(private val project: Project) : SettingsEditor<ByRunConfiguration>() {
    private val moduleField = JBTextField()
    private val environmentCombo = ByEnvironmentComboBox()
    private val workingDirField = TextFieldWithBrowseButton().apply {
        @Suppress("DEPRECATION")
        addBrowseFolderListener(
            "Working Directory",
            "Directory the by run command is invoked from",
            null,
            FileChooserDescriptorFactory.createSingleFolderDescriptor()
        )
    }

    /**
     * The program's own arguments, with the form behind the button.
     *
     * A module whose entry point is a `main` function has a command-line interface the IDE can
     * read, so the button offers it as fields; the text field stays the thing that is stored, and
     * stays editable, because not every module's arguments come from a `main`.
     */
    private val programArgsField = object : TextFieldWithBrowseButton({ editProgramArgs() }) {
        override fun getIconTooltip(): String = "Fill in main's parameters"
    }.apply { setButtonIcon(AllIcons.Actions.Edit) }
    private val extraArgsField = JBTextField()
    private val pythonVersionField = JBTextField()
    private val envVarsComponent = EnvironmentVariablesComponent()

    private val panel: JPanel = FormBuilder.createFormBuilder()
        .addLabeledComponent("Module:", moduleField)
        .addLabeledComponent("Environment:", environmentCombo)
        .addLabeledComponent("Working directory:", workingDirField)
        .addLabeledComponent("Program arguments:", programArgsField)
        .addLabeledComponent("Extra args:", extraArgsField)
        .addLabeledComponent("Min Python version:", pythonVersionField)
        .addComponent(envVarsComponent)
        .panel

    override fun resetEditorFrom(s: ByRunConfiguration) {
        val o = s.options
        moduleField.text = o.module
        environmentCombo.kind = o.environmentKind
        workingDirField.text = o.workingDir
        programArgsField.text = o.programArgs
        extraArgsField.text = o.extraArgs
        pythonVersionField.text = o.pythonVersion
        envVarsComponent.envs = o.envVars
        envVarsComponent.isPassParentEnvs = o.passParentEnv
    }

    override fun applyEditorTo(s: ByRunConfiguration) {
        val o = s.options
        o.module = moduleField.text.trim()
        o.environmentKind = environmentCombo.kind
        o.workingDir = workingDirField.text.trim()
        o.programArgs = programArgsField.text.trim()
        o.extraArgs = extraArgsField.text.trim()
        o.pythonVersion = pythonVersionField.text.trim()
        o.envVars = LinkedHashMap(envVarsComponent.envs)
        o.passParentEnv = envVarsComponent.isPassParentEnvs
    }

    override fun createEditor(): JComponent = panel

    /**
     * Opens the argument form for the module named right now — the field, not the saved value, so
     * it follows a module the user has just retyped.
     */
    private fun editProgramArgs() {
        val module = moduleField.text.trim()
        val main = module.takeIf { it.isNotBlank() }?.let { ByMainModules.mainFor(project, it) }
        if (main == null || !main.takesArguments) {
            Messages.showInfoMessage(project, unavailable(module, main == null), "Program Arguments")
            return
        }
        val dialog = ByMainArgumentsDialog(project, module, main, programArgsField.text)
        if (dialog.showAndGet()) programArgsField.text = dialog.result().arguments
    }

    private fun unavailable(module: String, unresolved: Boolean): String = when {
        module.isBlank() -> "Name a module first — its `main` function is the command-line interface."
        unresolved -> "No `main` function to fill in: `$module` has none, declares one the command " +
            "line cannot supply, or invokes it itself."
        else -> "`$module` takes no arguments: its `main` declares no parameters the command line fills."
    }
}
