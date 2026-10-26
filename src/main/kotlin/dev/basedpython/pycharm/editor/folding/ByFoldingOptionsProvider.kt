package dev.basedpython.pycharm.editor.folding

import com.intellij.application.options.editor.CodeFoldingOptionsProvider
import com.intellij.openapi.options.BeanConfigurable
import dev.basedpython.pycharm.util.BasedPythonBundle

/** The basedpython section of *Settings | Editor | General | Code Folding*: [ByFoldingSettings]. */
class ByFoldingOptionsProvider :
    BeanConfigurable<ByFoldingSettings.State>(ByFoldingSettings.getInstance().state, BasedPythonBundle.message("folding.options.title")),
    CodeFoldingOptionsProvider {

    init {
        val state = ByFoldingSettings.getInstance().state
        checkBox(BasedPythonBundle.message("folding.options.longStrings"), state::collapseLongStrings)
        checkBox(BasedPythonBundle.message("folding.options.longCollections"), state::collapseLongCollections)
        checkBox(BasedPythonBundle.message("folding.options.sequentialComments"), state::collapseSequentialComments)
        checkBox(BasedPythonBundle.message("folding.options.typeAnnotations"), state::collapseTypeAnnotations)
    }
}
