package dev.basedpython.pycharm.env.manager

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import dev.basedpython.pycharm.util.BasedPythonBundle
import javax.swing.JComponent

/**
 * "Add package": a requirement line and whether it is a development dependency.
 *
 * A free-text field rather than a searchable index of PyPI, on purpose. What the user types goes
 * through to the backend untouched, so every form of requirement the tool understands works here on
 * the first day — `httpx`, `httpx>=0.27`, `httpx[http2]`, a git URL, a local path — and none of it
 * has to be modelled, kept up to date, or explained. A package browser is a nice thing to have and a
 * bad thing to have *instead* of this.
 */
internal class EnvAddPackageDialog(project: Project) : DialogWrapper(project) {

    /** What the dialog asks for. */
    data class Request(val requirements: List<String>, val dev: Boolean)

    private val field = JBTextField(30)
    private val devCheckBox = JBCheckBox(BasedPythonBundle.message("env.add.dev"))

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
        .addComponent(devCheckBox)
        .panel
        .apply { border = JBUI.Borders.empty(8) }

    override fun getPreferredFocusedComponent(): JComponent = field

    override fun doValidate(): ValidationInfo? =
        if (EnvRequirements.split(field.text).isEmpty()) {
            ValidationInfo(BasedPythonBundle.message("env.add.empty"), field)
        } else {
            null
        }

    /** Shows the dialog; null when the user cancelled. Must be called on the EDT. */
    fun ask(): Request? {
        if (!showAndGet()) return null
        return Request(EnvRequirements.split(field.text), devCheckBox.isSelected)
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
