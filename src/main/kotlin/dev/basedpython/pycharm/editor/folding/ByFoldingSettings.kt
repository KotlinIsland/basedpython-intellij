package dev.basedpython.pycharm.editor.folding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * What *Settings | Editor | General | Code Folding | basedpython* folds by default, for the kinds of
 * range `by` sends that the platform's own *Fold by default* options do not cover. The same four
 * PyCharm offers for Python, off by default as Python's are.
 */
@Service(Service.Level.APP)
@State(name = "BasedPythonFoldingSettings", storages = [Storage("editor.codeinsight.xml")])
class ByFoldingSettings : PersistentStateComponent<ByFoldingSettings.State> {

    data class State(
        /** A string literal spanning lines, other than a docstring: `by`'s `string` kind. */
        var collapseLongStrings: Boolean = false,
        /** The inside of a display or comprehension spanning lines: `collection`. */
        var collapseLongCollections: Boolean = false,
        /** A run of comments on lines of their own: `comment`. */
        var collapseSequentialComments: Boolean = false,
        /** A parameter's or variable's annotation, or what a `def` returns: `annotation`. */
        var collapseTypeAnnotations: Boolean = false,
    )

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    companion object {
        fun getInstance(): ByFoldingSettings = ApplicationManager.getApplication().getService(ByFoldingSettings::class.java)
    }
}
