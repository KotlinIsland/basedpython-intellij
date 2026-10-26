package dev.basedpython.pycharm.run

import com.intellij.execution.configuration.EnvironmentVariablesComponent
import com.intellij.execution.ui.BeforeRunComponent
import com.intellij.execution.ui.BeforeRunFragment
import com.intellij.execution.ui.CommonParameterFragments
import com.intellij.execution.ui.CommonTags
import com.intellij.execution.ui.RunConfigurationFragmentedEditor
import com.intellij.execution.ui.SettingsEditorFragment
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.LabeledComponent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.ui.components.JBTextField
import dev.basedpython.pycharm.run.main.ByMainArgumentsDialog
import dev.basedpython.pycharm.run.model.ByProgramModel
import dev.basedpython.pycharm.util.BasedPythonBundle
import java.awt.BorderLayout
import javax.swing.JComponent

/**
 * The `by run` configuration's editor, in the platform's fragments as every run configuration of
 * `.py`'s and Java's is: the fields a run needs always shown, and the rest — *Redirect input*,
 * *Emulate terminal in output console*, `by`'s own options, the minimum Python version — added from
 * *Modify options*, with the platform's before-launch tasks and tags.
 *
 * `.env` files are the platform's: the environment variables field takes them, as Java's does, and
 * they are read when the run starts ([ByRunOptions.envFilePaths]).
 */
class ByRunFragmentedEditor(configuration: ByRunConfiguration) : RunConfigurationFragmentedEditor<ByRunConfiguration>(configuration) {

    override fun createRunFragments(): List<SettingsEditorFragment<ByRunConfiguration, *>> {
        val beforeRun = BeforeRunComponent(this)
        val module = JBTextField().apply { emptyText.text = BasedPythonBundle.message("run.editor.module.empty") }
        val programArgs = object : TextFieldWithBrowseButton() {
            override fun getIconTooltip(): String = BasedPythonBundle.message("run.editor.programArgs.fill")
        }.apply { setButtonIcon(AllIcons.Actions.Edit) }
        programArgs.addActionListener { editProgramArgs(module.text.trim(), programArgs) }
        val environment = ByEnvironmentComboBox()
        val workingDir = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.singleDir().withTitle(BasedPythonBundle.message("run.editor.workingDir.title")))
        }
        val env = EnvironmentVariablesComponent(project).apply { setLabelLocation(BorderLayout.WEST) }
        val input = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(project, FileChooserDescriptorFactory.singleFile())
        }
        val extraArgs = JBTextField()
        val pythonVersion = JBTextField()

        return listOf(
            CommonParameterFragments.createRunHeader(),
            BeforeRunFragment.createBeforeRun(beforeRun, null),
            *BeforeRunFragment.createGroup<ByRunConfiguration>().toTypedArray(),
            CommonTags.parallelRun(),
            field("by.module", BasedPythonBundle.message("run.editor.module"), null, module, { true }, { s -> module.text = s.options.module }) { s, _ ->
                s.options.module = module.text.trim()
            },
            field("by.programArgs", BasedPythonBundle.message("run.editor.programArgs"), null, programArgs, { true }, { s -> programArgs.text = s.options.programArgs }) { s, _ ->
                s.options.programArgs = programArgs.text.trim()
            },
            field("by.environment", BasedPythonBundle.message("run.editor.environment"), null, environment, { true }, { s -> environment.kind = s.options.environmentKind }) { s, _ ->
                s.options.environmentKind = environment.kind
            },
            field("workingDirectory", BasedPythonBundle.message("run.editor.workingDir"), null, workingDir, { true }, { s -> workingDir.text = s.options.workingDir }) { s, _ ->
                s.options.workingDir = workingDir.text.trim()
            },
            SettingsEditorFragment<ByRunConfiguration, EnvironmentVariablesComponent>(
                "environmentVariables",
                BasedPythonBundle.message("run.editor.envVars"),
                BasedPythonBundle.message("run.editor.group.os"),
                env,
                { s, c ->
                    c.envs = s.options.envVars
                    c.isPassParentEnvs = s.options.passParentEnv
                    c.envFilePaths = s.options.envFilePaths
                },
                { s, c ->
                    val shown = c.isVisible
                    s.options.envVars = if (shown) LinkedHashMap(c.envs) else LinkedHashMap()
                    s.options.passParentEnv = !shown || c.isPassParentEnvs
                    s.options.envFilePaths = if (shown) c.envFilePaths.toMutableList() else mutableListOf()
                },
                { true },
            ).apply { isCanBeHidden = true },
            field(
                "by.redirectInput",
                BasedPythonBundle.message("run.editor.redirectInput"),
                BasedPythonBundle.message("run.editor.group.os"),
                input,
                { s -> s.options.redirectInput },
                { s -> input.text = s.options.inputFile },
            ) { s, shown ->
                s.options.inputFile = input.text.trim()
                s.options.redirectInput = shown && s.options.inputFile.isNotEmpty()
            },
            field(
                "by.extraArgs",
                BasedPythonBundle.message("run.editor.extraArgs"),
                BasedPythonBundle.message("run.editor.group"),
                extraArgs,
                { s -> s.options.extraArgs.isNotBlank() },
                { s -> extraArgs.text = s.options.extraArgs },
            ) { s, shown -> s.options.extraArgs = if (shown) extraArgs.text.trim() else "" },
            field(
                "by.pythonVersion",
                BasedPythonBundle.message("run.editor.pythonVersion"),
                BasedPythonBundle.message("run.editor.group"),
                pythonVersion,
                { s -> s.options.pythonVersion.isNotBlank() },
                { s -> pythonVersion.text = s.options.pythonVersion },
            ) { s, shown -> s.options.pythonVersion = if (shown) pythonVersion.text.trim() else "" },
            SettingsEditorFragment.createTag(
                "by.emulateTerminal",
                BasedPythonBundle.message("run.editor.emulateTerminal"),
                BasedPythonBundle.message("run.editor.group"),
                { s: ByRunConfiguration -> s.options.emulateTerminal },
                { s: ByRunConfiguration, on: Boolean -> s.options.emulateTerminal = on },
            ),
        )
    }

    /**
     * A field shown under its [label]; one with a [group] can be removed and added back from
     * *Modify options*, and is shown at first where [shown] says so. [apply] is told whether it is
     * shown, since a field taken away stands for its option unset.
     */
    private fun <C : JComponent> field(
        id: String,
        label: String,
        group: String?,
        component: C,
        shown: (ByRunConfiguration) -> Boolean,
        reset: (ByRunConfiguration) -> Unit,
        apply: (configuration: ByRunConfiguration, shown: Boolean) -> Unit,
    ): SettingsEditorFragment<ByRunConfiguration, LabeledComponent<C>> {
        val labeled = LabeledComponent.create(component, label, BorderLayout.WEST)
        return SettingsEditorFragment<ByRunConfiguration, LabeledComponent<C>>(
            id,
            label.removeSuffix(":"),
            group,
            labeled,
            { s, _ -> reset(s) },
            { s, c -> apply(s, c.isVisible) },
            { s -> shown(s) },
        ).apply {
            isRemovable = group != null
            isCanBeHidden = group != null
        }
    }

    /**
     * Opens the argument form for the module named right now — the field, not the saved value, so
     * it follows a module the user has just retyped.
     *
     * A module whose entry point is a `main` function has a command-line interface the IDE can read,
     * so the button offers it as fields; the text field stays the thing that is stored, and stays
     * editable, because not every module's arguments come from a `main`.
     */
    private fun editProgramArgs(module: String, field: TextFieldWithBrowseButton) {
        val main = module.takeIf { it.isNotBlank() }
            ?.let { ByProgramModel.getInstance(project).mainForWaiting(it, "Reading main's parameters") }
        if (main == null || !main.takesArguments) {
            Messages.showInfoMessage(project, unavailable(module, main == null), "Program Arguments")
            return
        }
        val dialog = ByMainArgumentsDialog(project, module, main, field.text)
        if (dialog.showAndGet()) field.text = dialog.result().arguments
    }

    private fun unavailable(module: String, unresolved: Boolean): String = when {
        module.isBlank() -> "Name a module first — its `main` function is the command-line interface."
        unresolved -> "No `main` function to fill in: `$module` has none, declares one the command " +
            "line cannot supply, or invokes it itself."
        else -> "`$module` takes no arguments: its `main` declares no parameters the command line fills."
    }
}
