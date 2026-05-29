package dev.basedpython.pycharm.facet

import com.intellij.facet.FacetConfiguration
import com.intellij.facet.ui.FacetEditorContext
import com.intellij.facet.ui.FacetEditorTab
import com.intellij.facet.ui.FacetValidatorsManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.util.xmlb.XmlSerializerUtil

class BasedPythonFacetConfiguration :
    FacetConfiguration,
    PersistentStateComponent<BasedPythonFacetConfiguration.State> {

    class State {
        var minPythonVersion: String = ""
        var extraArgs: String = ""
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, this.state)
    }

    override fun createEditorTabs(
        editorContext: FacetEditorContext,
        validatorsManager: FacetValidatorsManager,
    ): Array<FacetEditorTab> = arrayOf(BasedPythonFacetEditorTab(this))
}
