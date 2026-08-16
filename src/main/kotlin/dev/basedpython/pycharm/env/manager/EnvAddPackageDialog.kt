package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * "Add package": a requirement line, and which of the project's dependency lists it joins.
 *
 * A free-text field rather than a searchable index of PyPI, on purpose. What the user types goes
 * through to the backend untouched, so every form of requirement the tool understands works here on
 * the first day — `httpx`, `httpx>=0.27`, `httpx[http2]`, a git URL, a local path — and none of it
 * has to be modelled, kept up to date, or explained. A package browser is a nice thing to have and a
 * bad thing to have *instead* of this.
 *
 * The group is a combo box seeded from the groups the project already declares, and is editable, so
 * a group that does not exist yet can be typed and the backend will create it. Its initial value is
 * whatever was selected in the tree — selecting `dev` and pressing *Add* should add to `dev`.
 */
internal class EnvAddPackageDialog(
    project: Project,
    private val initialTarget: EnvDependencyTarget,
    existingTargets: List<EnvDependencyTarget>,
) : DialogWrapper(project) {

    /** What the dialog asks for. */
    data class Request(val requirements: List<String>, val target: EnvDependencyTarget)

    private val field = JBTextField(30)

    /**
     * The lists a requirement can join, as text.
     *
     * Text rather than [EnvDependencyTarget] because this combo is editable — a new group can be
     * typed — and an editable combo renders its selected value through its *editor*, which calls
     * `toString()`, not through any renderer set on it. A typed model therefore put `Group(name=dev)`
     * on screen. [EnvTargetLabels] owns the mapping in both directions.
     */
    private val targetBox = ComboBox(EnvTargetLabels.options(existingTargets, initialTarget).toTypedArray()).apply {
        isEditable = true
        selectedItem = EnvTargetLabels.format(initialTarget)
    }

    init {
        title = BasedPythonBundle.message("env.add.title")
        setOKButtonText(BasedPythonBundle.message("env.add.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
        .addLabeledComponent(BasedPythonBundle.message("env.add.label"), field)
        .addComponentToRightColumn(
            JBLabel(BasedPythonBundle.message("env.add.hint")).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = JBColor.GRAY
            },
        )
        .addLabeledComponent(BasedPythonBundle.message("env.add.target"), targetBox)
        .addComponentToRightColumn(
            JBLabel(BasedPythonBundle.message("env.add.target.hint")).apply {
                componentStyle = UIUtil.ComponentStyle.SMALL
                foreground = JBColor.GRAY
            },
        )
        .panel
        .apply { border = JBUI.Borders.empty(8) }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? = when {
        EnvRequirements.split(field.text).isEmpty() ->
            ValidationInfo(BasedPythonBundle.message("env.add.empty"), field)
        selectedTarget() == null ->
            ValidationInfo(BasedPythonBundle.message("env.add.target.empty"), targetBox)
        else -> null
    }

    /** Shows the dialog; null when the user cancelled. Must be called on the EDT. */
    fun ask(): Request? {
        if (!showAndGet()) return null
        return Request(EnvRequirements.split(field.text), selectedTarget() ?: initialTarget)
    }

    /** The chosen list, whether it was picked from the list or typed. */
    private fun selectedTarget(): EnvDependencyTarget? =
        EnvTargetLabels.parse(targetBox.selectedItem?.toString().orEmpty())
}

/** Turning what the user typed into arguments. */
internal object EnvRequirements {

    /**
     * Splits a typed line into requirements.
     *
     * Whitespace-separated, because `uv add` itself takes several and pasting a line out of a README
     * is how this will be used. Commas are deliberately **not** separators: they are meaningful
     * inside a version specifier (`httpx>=0.27,<1.0`), and splitting on them would quietly turn one
     * correct requirement into two broken ones.
     */
    fun split(text: String): List<String> =
        text.split(' ', '\t', '\n').map { it.trim() }.filter { it.isNotEmpty() }
}
