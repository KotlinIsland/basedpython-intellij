package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
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
     * The lists a requirement can join.
     *
     * Always offers the main list and `dev`, whether or not the project declares them yet: they are
     * the two answers for almost every add, and a project that has neither is exactly the project
     * about to gain its first one.
     */
    private val targets: List<EnvDependencyTarget> = buildList {
        add(EnvDependencyTarget.Main)
        add(EnvDependencyTarget.DEV)
        existingTargets.forEach { if (it !in this) add(it) }
        if (initialTarget !in this) add(initialTarget)
    }

    private val targetBox = ComboBox(targets.toTypedArray()).apply {
        isEditable = true
        renderer = SimpleListCellRenderer.create("") { it?.let(::describe).orEmpty() }
        selectedItem = initialTarget
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

    /**
     * The chosen list.
     *
     * The combo is editable, so its value is a [EnvDependencyTarget] when the user picked one and a
     * typed [String] when they did not. A typed name is read as a dependency group — the thing that
     * can be created on demand. An extra cannot be conjured that way, which is why extras are only
     * ever offered, never typed: adding to `[project.optional-dependencies]` is a decision about the
     * package's public interface, not a place to put a test dependency.
     */
    private fun selectedTarget(): EnvDependencyTarget? = when (val value = targetBox.selectedItem) {
        is EnvDependencyTarget -> value
        is String -> value.trim().takeIf { it.isNotEmpty() }?.let { EnvDependencyTarget.Group(it) }
        else -> null
    }

    private fun describe(target: EnvDependencyTarget): String = when (target) {
        EnvDependencyTarget.Main -> BasedPythonBundle.message("env.add.target.main")
        is EnvDependencyTarget.Group -> BasedPythonBundle.message("env.add.target.group", target.name)
        is EnvDependencyTarget.Extra -> BasedPythonBundle.message("env.add.target.extra", target.name)
    }
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
