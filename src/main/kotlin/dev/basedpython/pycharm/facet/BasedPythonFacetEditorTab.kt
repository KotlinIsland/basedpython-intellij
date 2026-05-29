package dev.basedpython.pycharm.facet

import com.intellij.facet.ui.FacetEditorTab
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

class BasedPythonFacetEditorTab(
    private val config: BasedPythonFacetConfiguration,
) : FacetEditorTab() {

    private val minPythonVersionField = JBTextField()
    private val extraArgsField = JBTextField()

    override fun createComponent(): JComponent = panel {
        row("Minimum Python version:") {
            cell(minPythonVersionField).resizableColumn()
        }
        row("Extra arguments:") {
            cell(extraArgsField).resizableColumn()
        }
    }

    override fun isModified(): Boolean {
        val state = config.state
        return minPythonVersionField.text != state.minPythonVersion ||
            extraArgsField.text != state.extraArgs
    }

    override fun apply() {
        val state = config.state
        state.minPythonVersion = minPythonVersionField.text
        state.extraArgs = extraArgsField.text
    }

    override fun reset() {
        val state = config.state
        minPythonVersionField.text = state.minPythonVersion
        extraArgsField.text = state.extraArgs
    }

    override fun getDisplayName(): String = "BasedPython"
}
